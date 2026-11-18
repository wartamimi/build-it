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
import dev.buildit.engine.blocks.Component
import dev.buildit.engine.blocks.ComponentType
import dev.buildit.engine.blocks.BlockSync
import dev.buildit.engine.blocks.BlockValidator
import dev.buildit.engine.blocks.Procedures
import dev.buildit.engine.blocks.Slot
import dev.buildit.engine.blocks.defOf
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
    /** Layout of each activity (activity name -> layout name). */
    val layouts: Map<String, String> = emptyMap(),
    val activity: String? = null,
    val document: BlockDocument? = null,
    val views: List<ViewInfo> = emptyList(),
    /** Sound files in res/raw (resource names), for sound blocks. */
    val sounds: List<String> = emptyList(),
    /** Images in res/drawable, by resource name. */
    val images: List<String> = emptyList(),
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
                val (sounds, images) = withContext(dispatchers.io) { readSounds(p.directory) to readImages(p.directory) }
                editor = BlockEditor(doc)
                _state.update {
                    it.copy(
                        loading = false, error = null, activities = activities.map { a -> a.name }, activity = chosen.name,
                        layouts = activities.associate { a -> a.name to a.layout },
                        document = doc, views = views, sounds = sounds, images = images, selectedId = null, canUndo = false, canRedo = false, dirty = false,
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

    /** Re-reads the project's activities (one may have been added in the designer) without reloading the blocks. */
    fun refreshActivities() {
        viewModelScope.launch {
            val m = try { repository.open(projectId).manifest } catch (_: Exception) { return@launch }
            manifest = m
            val activities = m.modules.flatMap { it.activities }
            _state.update { it.copy(activities = activities.map { a -> a.name }, layouts = activities.associate { a -> a.name to a.layout }) }
        }
    }

    /** Re-reads the layout's views (they may have changed in the designer) without touching the blocks. */
    fun refreshViews() {
        val dir = projectDir ?: return
        val m = manifest ?: return
        val activity = m.modules.flatMap { it.activities }.firstOrNull { it.name == _state.value.activity } ?: return
        viewModelScope.launch {
            val views = withContext(dispatchers.io) { readViews(File(dir, "app/src/main/res/layout/${activity.layout}.xml")) }
            val (sounds, images) = withContext(dispatchers.io) { readSounds(dir) to readImages(dir) }
            _state.update { it.copy(views = views, sounds = sounds, images = images) }
            refreshDiagnostics()
        }
    }

    private fun context() = BlockContext(_state.value.views, _state.value.activities, _state.value.sounds, _state.value.images)

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
    /** A palette block dropped on the canvas: into the slot [slotFor] finds, else as a new stack at ([x], [y]). */
    fun drop(type: String, x: Float, y: Float, slotFor: (BlockDocument, List<Block>) -> Slot?) {
        if (BlockRegistry.find(type) == null) return
        place(type, x, y) { d, block -> slotFor(d, listOf(block)) }
    }

    fun add(type: String, x: Float, y: Float) {
        val selected = _state.value.selectedId?.let { _state.value.document?.find(it) }
        val shape = BlockRegistry[type].shape
        place(type, x, y) { d, block -> selected?.let { sel -> preferredSlot(d, sel, shape, block) } }
    }

    private fun place(type: String, x: Float, y: Float, slotFor: (BlockDocument, Block) -> Slot?) {
        val doc = _state.value.document ?: return
        val created = BlockRegistry.create(type, ids, doc.variables, _state.value.views, doc.components, _state.value.sounds, _state.value.images)
        val selected = _state.value.selectedId?.let { doc.find(it) }
        val procedure = doc.stacks.mapNotNull { s -> s.blocks.firstOrNull()?.takeIf { it.type == "proc_define" } }
        val block = when (type) {
            // A call starts on the first procedure; a parameter on the first parameter of the selected procedure
            "proc_call" -> procedure.firstOrNull()?.let { created.copy(fields = mapOf("name" to it.field("name"))) } ?: created
            "proc_param" -> selected?.let { Procedures.enclosingDefine(doc, it.id) }?.let { Procedures.parse(it.field("params"))?.firstOrNull() }
                ?.let { created.copy(fields = mapOf("param" to it.name)) } ?: created
            "proc_define" -> created.copy(fields = created.fields + ("name" to freeProcedureName(procedure.map { it.field("name") })))
            else -> created
        }
        edit { d ->
            val slot = slotFor(d, block)?.takeIf { BlockOps.canInsert(d, listOf(block), it) }
            val next = if (slot != null) BlockOps.insert(d, listOf(block), slot, x to y) else BlockOps.addStack(d, listOf(block), x, y)
            if (type == "proc_call") Procedures.syncCalls(next, block.field("name"), ids) else next
        }
        _state.update { it.copy(selectedId = block.id) }
    }

    private fun preferredSlot(d: BlockDocument, sel: Block, shape: BlockShape, block: Block): Slot? {
        val selDef = d.defOf(sel) ?: return null
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

    fun setField(id: String, name: String, value: String) = edit { d ->
        val block = d.find(id)
        val next = BlockOps.setField(d, id, name, value)
        when {
            // Calls follow their procedure's parameters (and its new name)
            block?.type == "proc_define" && name == "name" -> {
                val old = block.field("name")
                val renamed = next.allBlocks().filter { it.type == "proc_call" && it.field("name") == old }
                    .fold(next) { acc, c -> BlockOps.setField(acc, c.id, "name", value) }
                Procedures.syncCalls(renamed, value, ids)
            }
            block?.type == "proc_define" && name == "params" -> Procedures.syncCalls(next, block.field("name"), ids)
            block?.type == "proc_call" && name == "name" -> Procedures.syncCalls(next, value, ids)
            else -> next
        }
    }

    private fun freeProcedureName(taken: List<String>) =
        generateSequence(1) { it + 1 }.map { if (it == 1) "doSomething" else "doSomething$it" }.first { it !in taken }
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

    // ------------------------------------------------------------ events and components

    /**
     * Adds an event block (with [fields], e.g. the view it listens to) below the existing stacks,
     * or finds the one already there. Returns the event block's id, or null if nothing is open.
     */
    fun addEvent(type: String, fields: Map<String, String> = emptyMap()): String? {
        val doc = _state.value.document ?: return null
        doc.stacks.firstOrNull { s -> s.blocks.firstOrNull()?.let { h -> h.type == type && fields.all { (k, v) -> h.field(k) == v } } == true }
            ?.let { return it.blocks.first().id }
        val base = BlockRegistry.create(type, ids, doc.variables, _state.value.views, doc.components, _state.value.sounds, _state.value.images)
        val block = base.copy(fields = base.fields + fields)
        // Below the lowest stack, estimating each block's height generously so nothing overlaps.
        val y = doc.stacks.maxOfOrNull { st -> st.y + st.blocks.sumOf { b -> b.walk().count() } * 56f } ?.plus(EVENT_SPACING) ?: 40f
        edit { d -> BlockOps.addStack(d, listOf(block), 40f, y) }
        _state.update { it.copy(selectedId = block.id) }
        return block.id
    }

    fun addComponent(name: String, type: ComponentType) = edit { BlockOps.addComponent(it, Component(name.trim(), type)) }
    fun renameComponent(old: String, new: String) = edit { BlockOps.renameComponent(it, old, new.trim()) }
    fun removeComponent(name: String) = edit { BlockOps.removeComponent(it, name) }

    /** A free name like `timer1` for a new component of [type]. */
    fun suggestComponentName(type: ComponentType): String {
        val doc = _state.value.document
        val taken = doc?.components.orEmpty().map { it.name }.toSet() + doc?.variables.orEmpty().map { it.name } + _state.value.views.map { it.id }
        val base = type.name.lowercase()
        return generateSequence(1) { it + 1 }.map { "$base$it" }.first { it !in taken }
    }

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
        private const val EVENT_SPACING = 120f

        /** Resource names of the files in res/raw. */
        fun readSounds(projectDir: File): List<String> =
            File(projectDir, "app/src/main/res/raw").listFiles().orEmpty().filter { it.isFile }.map { it.name.substringBefore('.') }.distinct().sorted()

        /** Resource names of the images in res/drawable. */
        fun readImages(projectDir: File): List<String> =
            dev.buildit.engine.project.ProjectAssets(projectDir).list(dev.buildit.engine.project.AssetKind.Image).map { it.name }.distinct()

        fun readViews(layout: File): List<ViewInfo> {
            if (!layout.isFile) return emptyList()
            val root = runCatching { XmlParser.parse(layout.readText()) }.getOrNull() ?: return emptyList()
            return root.walk().mapNotNull { e -> e[Attr.ID]?.let { ViewInfo(LayoutEditor.idName(it), e.name) } }.distinctBy { it.id }.toList()
        }
    }
}
