package dev.buildit.feature.blocks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.buildit.core.common.DispatcherProvider
import dev.buildit.core.data.ProjectRepository
import dev.buildit.core.data.SettingsRepository
import dev.buildit.engine.blocks.Block
import dev.buildit.engine.blocks.BlockContext
import dev.buildit.engine.blocks.BlockDiagnostic
import dev.buildit.engine.blocks.BlockDocument
import dev.buildit.engine.blocks.BlockEditException
import dev.buildit.engine.blocks.BlockEditor
import dev.buildit.engine.blocks.BlockOps
import dev.buildit.engine.blocks.BlockRegistry
import dev.buildit.engine.blocks.BlockShape
import dev.buildit.engine.blocks.BlockStore
import dev.buildit.engine.blocks.BlockSync
import dev.buildit.engine.blocks.BlockValidator
import dev.buildit.engine.blocks.Slot
import dev.buildit.engine.blocks.ValueType
import dev.buildit.engine.blocks.Variable
import dev.buildit.engine.blocks.ViewInfo
import dev.buildit.engine.layout.Attr
import dev.buildit.engine.layout.LayoutEditor
import dev.buildit.engine.layout.XmlParser
import dev.buildit.engine.project.ProjectManifest
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** State of the generated source relative to the blocks. */
sealed interface CodeStatus {
    data object Unknown : CodeStatus
    data object UpToDate : CodeStatus
    /** Blocks have errors, so the source was not regenerated. */
    data class HasErrors(val count: Int) : CodeStatus
    /** The source file was edited by hand; Build It is waiting for the user's decision. */
    data class HandEdited(val path: String) : CodeStatus
}

data class BlocksUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val activities: List<String> = emptyList(),
    val activity: String? = null,
    val document: BlockDocument? = null,
    val views: List<ViewInfo> = emptyList(),
    val diagnostics: List<BlockDiagnostic> = emptyList(),
    val selectedId: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val codeStatus: CodeStatus = CodeStatus.Unknown,
    /** Generated source for the preview (null while the blocks have errors). */
    val code: String? = null,
    val sourcePath: String? = null,
    /** The user chose to keep their hand-edited source; don't ask again in this session. */
    val keepHandEdits: Boolean = false,
)

sealed interface BlocksMessage {
    data class Rejected(val code: String) : BlocksMessage
    data class Failed(val detail: String) : BlocksMessage
}

class BlocksViewModel(
    private val projectId: String,
    private val repository: ProjectRepository,
    private val settings: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    private val ids: () -> String = { UUID.randomUUID().toString().substring(0, 12) },
) : ViewModel() {
    private val _state = MutableStateFlow(BlocksUiState())
    val state: StateFlow<BlocksUiState> = _state.asStateFlow()
    private val _messages = Channel<BlocksMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var projectDir: File? = null
    private var manifest: ProjectManifest? = null
    private var store: BlockStore? = null
    private var editor: BlockEditor? = null
    private var saveJob: Job? = null

    init { load(null) }

    /** (Re)loads the project, the activity's blocks and the views of its layout. */
    fun load(activity: String?) {
        viewModelScope.launch {
            flushNow()
            try {
                val p = repository.open(projectId)
                projectDir = p.directory
                manifest = p.manifest
                store = BlockStore(p.directory)
                val activities = p.manifest.modules.flatMap { it.activities }
                val chosen = activities.firstOrNull { it.name == activity } ?: activities.firstOrNull { it.launcher } ?: activities.firstOrNull()
                if (chosen == null) {
                    _state.update { it.copy(loading = false, activities = emptyList(), activity = null, document = null) }
                    return@launch
                }
                val (doc, views) = withContext(dispatchers.io) {
                    store!!.load(chosen.name, ids) to readViews(File(p.directory, "app/src/main/res/layout/${chosen.layout}.xml"))
                }
                editor = BlockEditor(doc)
                _state.update {
                    it.copy(
                        loading = false, error = null, activities = activities.map { a -> a.name }, activity = chosen.name,
                        document = doc, views = views, selectedId = null, canUndo = false, canRedo = false, dirty = false,
                        codeStatus = CodeStatus.Unknown, keepHandEdits = false,
                        sourcePath = BlockSync.sourcePath(p.manifest, chosen.name),
                    )
                }
                refreshDiagnostics()
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Re-reads the layout's views (they may have changed in the designer) without touching the blocks. */
    fun refreshViews() {
        val dir = projectDir ?: return
        val m = manifest ?: return
        val activity = m.modules.flatMap { it.activities }.firstOrNull { it.name == _state.value.activity } ?: return
        viewModelScope.launch {
            val views = withContext(dispatchers.io) { readViews(File(dir, "app/src/main/res/layout/${activity.layout}.xml")) }
            _state.update { it.copy(views = views) }
            refreshDiagnostics()
        }
    }

    private fun context() = BlockContext(_state.value.views, _state.value.activities)

    private fun refreshDiagnostics() {
        val doc = _state.value.document ?: return
        val m = manifest ?: return
        val diagnostics = BlockValidator.validate(doc, context())
        val generated = BlockSync.generateCode(m, doc, context()) as? BlockSync.Result.Generated
        _state.update {
            it.copy(
                diagnostics = diagnostics,
                code = generated?.code,
                codeStatus = if (generated == null) CodeStatus.HasErrors(diagnostics.count { d -> d.severity == BlockDiagnostic.Severity.Error })
                else (it.codeStatus as? CodeStatus.HandEdited) ?: if (it.dirty) CodeStatus.Unknown else it.codeStatus,
            )
        }
    }

    // ------------------------------------------------------------ editing

    /** Applies an edit as one undo step. Rejected edits leave everything unchanged. */
    fun edit(change: (BlockDocument) -> BlockDocument) {
        val ed = editor ?: return
        try {
            ed.apply(change)
        } catch (e: BlockEditException) {
            viewModelScope.launch { _messages.send(BlocksMessage.Rejected(e.code)) }
            return
        }
        publish()
    }

    private fun publish() {
        val ed = editor ?: return
        val doc = ed.document
        _state.update {
            it.copy(
                document = doc, canUndo = ed.canUndo, canRedo = ed.canRedo, dirty = true,
                selectedId = it.selectedId?.takeIf { id -> doc.find(id) != null },
            )
        }
        refreshDiagnostics()
        scheduleSave()
    }

    fun undo() { if (editor?.undo() == true) publish() }
    fun redo() { if (editor?.redo() == true) publish() }
    fun select(id: String?) = _state.update { it.copy(selectedId = id) }

    /**
     * Adds a new block from the palette: after the selected statement, into the first empty
     * compatible input of the selected block, or as a new stack at ([x], [y]).
     */
    fun add(type: String, x: Float, y: Float) {
        val doc = _state.value.document ?: return
        val block = BlockRegistry.create(type, ids, doc.variables, _state.value.views)
        val shape = BlockRegistry[type].shape
        val selected = _state.value.selectedId?.let { doc.find(it) }
        edit { d ->
            val slot = selected?.let { sel -> preferredSlot(d, sel, shape, block) }
            if (slot != null) BlockOps.insert(d, listOf(block), slot, x to y) else BlockOps.addStack(d, listOf(block), x, y)
        }
        _state.update { it.copy(selectedId = block.id) }
    }

    private fun preferredSlot(d: BlockDocument, sel: Block, shape: BlockShape, block: Block): Slot? {
        val selDef = BlockRegistry.find(sel.type) ?: return null
        return when (shape) {
            BlockShape.Statement -> when {
                selDef.statements.isNotEmpty() && (selDef.shape == BlockShape.Hat || sel.statements.values.all { it.isEmpty() }) ->
                    Slot.Statement(sel.id, selDef.statements.first().name, sel.statements[selDef.statements.first().name].orEmpty().size)
                selDef.shape == BlockShape.Statement -> when (val loc = BlockOps.locate(d, sel.id)) {
                    is Slot.Statement -> loc.copy(index = loc.index + 1)
                    is Slot.TopLevel -> loc.copy(index = loc.index + 1)
                    else -> null
                }
                else -> null
            }?.takeIf { BlockOps.canInsert(d, listOf(block), it) }
            BlockShape.Value -> selDef.inputs.firstOrNull { i -> sel.inputs[i.name] == null || sel.inputs[i.name]!!.type.let { t -> t == "text_literal" || t == "math_number" || t == "logic_bool" } }
                ?.let { Slot.Value(sel.id, it.name) }?.takeIf { BlockOps.canInsert(d, listOf(block), it) }
            BlockShape.Hat -> null
        }
    }

    fun setField(id: String, name: String, value: String) = edit { BlockOps.setField(it, id, name, value) }
    fun delete(id: String) = edit { BlockOps.delete(it, id) }
    fun duplicate(id: String, x: Float, y: Float) = edit { BlockOps.duplicate(it, id, ids, x, y) }
    fun toggleDisabled(id: String) = edit { d -> BlockOps.setDisabled(d, id, !(d.find(id)?.disabled ?: false)) }

    /** Commits the result of a drag: [result] replaces [original] as a single undo step. */
    fun commitDrag(original: BlockDocument, result: BlockDocument) {
        if (editor?.document != original) return
        edit { result }
    }

    fun addVariable(name: String, type: ValueType) = edit { BlockOps.addVariable(it, Variable(name.trim(), type)) }
    fun renameVariable(old: String, new: String) = edit { BlockOps.renameVariable(it, old, new.trim()) }
    fun removeVariable(name: String) = edit { BlockOps.removeVariable(it, name) }

    fun newId(): String = ids()

    val autoSaveEnabled get() = settings.settings

    /** Drops unsaved block edits and reloads the saved blocks. */
    fun discard() {
        saveJob?.cancel()
        _state.update { it.copy(dirty = false) }
        load(_state.value.activity)
    }

    // ------------------------------------------------------------ saving

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (!settings.settings.first().autoSave) return@launch
            delay(SAVE_DELAY_MS)
            saveNow(force = false)
        }
    }

    fun save(force: Boolean = false) {
        if (force) _state.update { it.copy(keepHandEdits = false) }
        viewModelScope.launch { saveNow(force) }
    }

    fun flush() { viewModelScope.launch { flushNow() } }

    private suspend fun flushNow() {
        saveJob?.cancel()
        if (_state.value.dirty && settings.settings.first().autoSave) saveNow(force = false)
    }

    /** Saves the blocks, then regenerates the activity source when the blocks are valid. */
    private suspend fun saveNow(force: Boolean) {
        val doc = editor?.document ?: return
        val dir = projectDir ?: return
        val m = manifest ?: return
        _state.update { it.copy(saving = true) }
        try {
            val result = withContext(dispatchers.io) {
                store!!.save(doc)
                BlockSync.write(dir, m, doc, context(), force)
            }
            _state.update {
                it.copy(
                    saving = false,
                    dirty = editor?.document != doc,
                    codeStatus = when (result) {
                        is BlockSync.Result.Generated -> CodeStatus.UpToDate
                        is BlockSync.Result.Invalid -> CodeStatus.HasErrors(result.diagnostics.count { d -> d.severity == BlockDiagnostic.Severity.Error })
                        is BlockSync.Result.UserModified -> CodeStatus.HandEdited(result.path)
                    },
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(saving = false) }
            _messages.send(BlocksMessage.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    /** Saves and then runs [after] when nothing is left unsaved. */
    fun saveThen(after: () -> Unit) {
        viewModelScope.launch {
            saveNow(force = false)
            if (!_state.value.dirty) after()
        }
    }

    /** Keeps the hand-edited source: blocks stay saved, the source is not regenerated. */
    fun keepHandEditedSource() = _state.update { it.copy(keepHandEdits = true) }

    companion object {
        const val SAVE_DELAY_MS = 800L

        /** Views with an id in the layout, in document order. Unreadable layouts give no views. */
        fun readViews(layout: File): List<ViewInfo> {
            if (!layout.isFile) return emptyList()
            val root = runCatching { XmlParser.parse(layout.readText()) }.getOrNull() ?: return emptyList()
            return root.walk().mapNotNull { e -> e[Attr.ID]?.let { ViewInfo(LayoutEditor.idName(it), e.name) } }.distinctBy { it.id }.toList()
        }
    }
}
