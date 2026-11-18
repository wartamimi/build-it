package dev.buildit.feature.designer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.buildit.core.common.DispatcherProvider
import dev.buildit.core.data.ProjectRepository
import dev.buildit.core.data.SettingsRepository
import dev.buildit.engine.layout.ComponentCatalog
import dev.buildit.engine.layout.LayoutCommand
import dev.buildit.engine.layout.LayoutDiagnostic
import dev.buildit.engine.layout.LayoutEditException
import dev.buildit.engine.layout.LayoutEditor
import dev.buildit.engine.layout.ResourceValues
import dev.buildit.engine.layout.XmlElement
import dev.buildit.engine.layout.XmlParseException
import dev.buildit.engine.layout.XmlParser
import dev.buildit.engine.project.Activities
import dev.buildit.engine.project.AtomicFiles
import dev.buildit.engine.project.GeneratedFiles
import dev.buildit.engine.project.ProjectValidationException
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

data class DesignerUiState(
    val loading: Boolean = true,
    val layouts: List<String> = emptyList(),
    val current: String? = null,
    val root: XmlElement? = null,
    val selectedUid: Long? = null,
    val diagnostics: List<LayoutDiagnostic> = emptyList(),
    val parseError: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    /** The file changed on disk since it was loaded (e.g. edited in Files/Code). */
    val externalChange: Boolean = false,
    val preview: PreviewConfig = PreviewConfig(),
    val values: ResourceValues = ResourceValues.Empty,
    val resDir: File? = null,
    /** Bumps on every document change so the preview re-renders. */
    val revision: Int = 0,
)

sealed interface DesignerMessage {
    data class EditRejected(val code: String, val detail: String) : DesignerMessage
    data class Failed(val detail: String) : DesignerMessage
    data object Saved : DesignerMessage
    data class ActivityAdded(val name: String) : DesignerMessage
}

class DesignerViewModel(
    private val projectId: String,
    private val repository: ProjectRepository,
    private val settings: SettingsRepository,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(DesignerUiState())
    val state: StateFlow<DesignerUiState> = _state.asStateFlow()
    private val _messages = Channel<DesignerMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var projectDir: File? = null
    private var editor: LayoutEditor? = null
    private var loadedHash: String? = null
    private var autoSaveJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val p = repository.open(projectId)
                projectDir = p.directory
                val layouts = withContext(dispatchers.io) { Activities.layouts(p.directory) }
                val first = p.manifest.modules.firstOrNull()?.activities?.firstOrNull { it.launcher }?.layout?.takeIf { it in layouts }
                    ?: layouts.firstOrNull()
                _state.update { it.copy(layouts = layouts, resDir = resDir()) }
                if (first != null) open(first) else _state.update { it.copy(loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false) }
                _messages.send(DesignerMessage.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun resDir() = projectDir?.let { File(it, "app/src/main/res") }
    private fun layoutFile(name: String) = File(resDir(), "layout/$name.xml")

    fun open(name: String) {
        viewModelScope.launch {
            flushNow()
            _state.update { it.copy(loading = true) }
            val (text, values) = withContext(dispatchers.io) {
                layoutFile(name).readText() to ResourceValues.load(resDir()!!)
            }
            loadedHash = GeneratedFiles.sha256(text.toByteArray())
            try {
                val root = XmlParser.parse(text)
                editor = LayoutEditor(root)
                _state.update {
                    it.copy(
                        loading = false, current = name, root = root, selectedUid = null, parseError = null,
                        diagnostics = LayoutEditor.validate(root), canUndo = false, canRedo = false, dirty = false,
                        externalChange = false, values = values, revision = it.revision + 1,
                    )
                }
            } catch (e: XmlParseException) {
                editor = null
                _state.update { it.copy(loading = false, current = name, root = null, parseError = e.message, values = values) }
            }
        }
    }

    fun select(uid: Long?) = _state.update { it.copy(selectedUid = uid) }

    fun apply(command: LayoutCommand) {
        val ed = editor ?: return
        try {
            ed.apply(command)
        } catch (e: LayoutEditException) {
            viewModelScope.launch { _messages.send(DesignerMessage.EditRejected(e.code, e.message ?: "")) }
            return
        }
        val selected = when (command) {
            is LayoutCommand.Insert, is LayoutCommand.Duplicate -> ed.lastCreatedUid
            is LayoutCommand.Remove -> null
            else -> _state.value.selectedUid
        }
        publish(selected, dirty = true)
    }

    /**
     * Adds a component next to the selection: into the selected container, otherwise after the
     * selected view in its parent, otherwise at the end of the root.
     */
    fun addComponent(tag: String) {
        val root = editor?.root ?: return
        val sel = _state.value.selectedUid?.let { root.find(it) }
        val cmd = when {
            sel != null && ComponentCatalog.isContainer(sel.name) -> LayoutCommand.Insert(sel.uid, sel.elements.size, tag)
            sel != null && sel.uid != root.uid -> {
                val parent = root.parentOf(sel.uid)!!
                LayoutCommand.Insert(parent.uid, parent.elements.indexOfFirst { it.uid == sel.uid } + 1, tag)
            }
            else -> LayoutCommand.Insert(root.uid, root.elements.size, tag)
        }
        apply(cmd)
    }

    /** Moves the element one position up/down within its parent (component tree reordering). */
    fun shift(uid: Long, delta: Int) {
        val root = editor?.root ?: return
        val parent = root.parentOf(uid) ?: return
        val i = parent.elements.indexOfFirst { it.uid == uid }
        val target = i + delta
        if (target < 0 || target >= parent.elements.size) return
        apply(LayoutCommand.Move(uid, parent.uid, if (delta > 0) target + 1 else target))
    }

    fun undo() { if (editor?.undo() == true) publish(_state.value.selectedUid, dirty = true) }
    fun redo() { if (editor?.redo() == true) publish(_state.value.selectedUid, dirty = true) }

    fun setPreview(transform: (PreviewConfig) -> PreviewConfig) = _state.update { it.copy(preview = transform(it.preview)) }

    private fun publish(selected: Long?, dirty: Boolean) {
        val ed = editor ?: return
        val root = ed.root
        _state.update {
            it.copy(
                root = root, selectedUid = selected?.takeIf { s -> root.find(s) != null }, diagnostics = LayoutEditor.validate(root),
                canUndo = ed.canUndo, canRedo = ed.canRedo, dirty = dirty, revision = it.revision + 1,
            )
        }
        scheduleAutoSave()
    }

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            if (!settings.settings.first().autoSave) return@launch
            delay(AUTOSAVE_DELAY_MS)
            save()
        }
    }

    /** Saves now if there are unsaved changes (called when the screen stops). */
    fun flush() { viewModelScope.launch { flushNow() } }

    private suspend fun flushNow() {
        autoSaveJob?.cancel()
        if (_state.value.dirty && !_state.value.externalChange && settings.settings.first().autoSave) save()
    }

    fun save(force: Boolean = false) {
        viewModelScope.launch { saveInternal(force) }
    }

    private suspend fun save() = saveInternal(false)

    /** Saves, then runs [after] only if the save succeeded without a conflict. */
    fun saveThen(after: () -> Unit) {
        viewModelScope.launch {
            saveInternal(false)
            if (!_state.value.dirty) after()
        }
    }

    private suspend fun saveInternal(force: Boolean) {
        val ed = editor ?: return
        val name = _state.value.current ?: return
        _state.update { it.copy(saving = true) }
        try {
            val xml = ed.toXml()
            val conflict = withContext(dispatchers.io) {
                val file = layoutFile(name)
                val diskHash = if (file.isFile) GeneratedFiles.sha256(file.readBytes()) else null
                if (!force && diskHash != null && diskHash != loadedHash) {
                    true
                } else {
                    AtomicFiles.writeText(file, xml)
                    loadedHash = GeneratedFiles.sha256(xml.toByteArray())
                    false
                }
            }
            _state.update { it.copy(saving = false, dirty = conflict, externalChange = conflict) }
        } catch (e: Exception) {
            _state.update { it.copy(saving = false) }
            _messages.send(DesignerMessage.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    /** Drops unsaved designer edits (user chose "Discard") and reloads the file from disk. */
    fun discard() { _state.value.current?.let { _state.update { s -> s.copy(dirty = false) }; open(it) } }

    val autoSaveEnabled get() = settings.settings

    fun reloadFromDisk() { _state.value.current?.let { _state.update { s -> s.copy(dirty = false, externalChange = false) }; open(it) } }

    fun createLayout(name: String, rootTag: String) {
        viewModelScope.launch {
            try {
                if (!Activities.RESOURCE_NAME.matches(name)) throw IllegalArgumentException("invalid_resource")
                withContext(dispatchers.io) {
                    val f = layoutFile(name)
                    if (f.exists()) throw IllegalArgumentException("exists")
                    AtomicFiles.writeText(f, LayoutEditor.newLayout(rootTag))
                }
                refreshLayouts()
                open(name)
            } catch (e: Exception) {
                _messages.send(DesignerMessage.EditRejected(e.message ?: "failed", name))
            }
        }
    }

    fun addActivity(name: String, layout: String) {
        viewModelScope.launch {
            try {
                repository.addActivity(projectId, name, layout, LayoutEditor.newLayout("LinearLayout"))
                refreshLayouts()
                open(layout)
                _messages.send(DesignerMessage.ActivityAdded(name))
            } catch (e: ProjectValidationException) {
                val issue = e.issues.first()
                val code = if (issue.field == "activityName" && issue.code == "exists") "activity_exists" else issue.code
                _messages.send(DesignerMessage.EditRejected(code, issue.field))
            } catch (e: Exception) {
                _messages.send(DesignerMessage.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private suspend fun refreshLayouts() {
        val dir = projectDir ?: return
        val layouts = withContext(dispatchers.io) { Activities.layouts(dir) }
        _state.update { it.copy(layouts = layouts) }
    }

    companion object {
        const val AUTOSAVE_DELAY_MS = 800L
    }
}
