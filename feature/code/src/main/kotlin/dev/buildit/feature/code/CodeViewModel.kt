package dev.buildit.feature.code

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.buildit.core.common.DispatcherProvider
import dev.buildit.core.data.ProjectRepository
import dev.buildit.core.data.SettingsRepository
import dev.buildit.engine.editor.Checks
import dev.buildit.engine.editor.EditHistory
import dev.buildit.engine.editor.EditorDiagnostic
import dev.buildit.engine.editor.Language
import dev.buildit.engine.project.GeneratedFiles
import dev.buildit.engine.project.ProjectFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class OpenFile(
    val path: String,
    val language: Language,
    val dirty: Boolean = false,
    /** Build It generates this file (from blocks or settings); hand edits stop regeneration until confirmed. */
    val generated: Boolean = false,
    /** Changed on disk since it was opened here. */
    val conflict: Boolean = false,
    val diagnostics: List<EditorDiagnostic> = emptyList(),
    /** Bumps when the text is replaced from outside the editor (reload), so the view reloads it. */
    val revision: Int = 0,
)

data class Reveal(val path: String, val line: Int)

data class CodeUiState(
    val files: List<OpenFile> = emptyList(),
    val current: String? = null,
    val saving: Boolean = false,
    val fontSizeSp: Int = 14,
    /** A line to scroll to once [Reveal.path] is shown (e.g. from a build error). */
    val reveal: Reveal? = null,
) {
    val currentFile get() = files.firstOrNull { it.path == current }
}

sealed interface CodeMessage {
    data class Failed(val detail: String) : CodeMessage
    data class CannotOpen(val path: String, val detail: String) : CodeMessage
}

/**
 * Open files of the code editor. The live text of each file is held here (not in UI state, so
 * typing doesn't recompose the screen); saving checks the file on disk first and never overwrites
 * a change made elsewhere without asking.
 */
class CodeViewModel(
    private val projectId: String,
    private val repository: ProjectRepository,
    private val settings: SettingsRepository,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(CodeUiState())
    val state: StateFlow<CodeUiState> = _state.asStateFlow()
    private val _messages = Channel<CodeMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private class Buffer(var text: String, var diskText: String, val history: EditHistory = EditHistory())

    private val buffers = HashMap<String, Buffer>()
    private var projectDir: File? = null
    private var files: ProjectFiles? = null
    private val saveJobs = HashMap<String, Job>()
    private val checkJobs = HashMap<String, Job>()

    init {
        viewModelScope.launch { settings.settings.map { it.editorFontSizeSp }.collect { sp -> _state.update { it.copy(fontSizeSp = sp) } } }
    }

    private suspend fun project(): ProjectFiles {
        files?.let { return it }
        val p = repository.open(projectId)
        projectDir = p.directory
        return ProjectFiles(p.directory).also { files = it }
    }

    fun text(path: String): String? = buffers[path]?.text
    fun history(path: String): EditHistory? = buffers[path]?.history

    /** Opens [path] (relative to the project) in a tab, or switches to it when already open. */
    fun open(path: String) {
        if (buffers.containsKey(path)) { _state.update { it.copy(current = path) }; return }
        viewModelScope.launch {
            try {
                val pf = project()
                val (text, generated) = withContext(dispatchers.io) { pf.readText(path) to GeneratedFiles(projectDir!!).isTracked(path) }
                buffers[path] = Buffer(text, text)
                val lang = Language.forPath(path)
                _state.update { s -> s.copy(files = s.files + OpenFile(path, lang, generated = generated), current = path) }
                check(path)
            } catch (e: Exception) {
                _messages.send(CodeMessage.CannotOpen(path, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun select(path: String) = _state.update { it.copy(current = path) }

    /** Opens [path] and asks the screen to move the cursor to 1-based [line]. */
    fun reveal(path: String, line: Int) {
        _state.update { it.copy(reveal = Reveal(path, line)) }
        open(path)
    }

    fun revealDone() = _state.update { it.copy(reveal = null) }

    /** Called by the editor on every change. */
    fun onEdited(path: String, text: String) {
        val b = buffers[path] ?: return
        if (b.text == text) return
        b.text = text
        val dirty = text != b.diskText
        if (_state.value.files.firstOrNull { it.path == path }?.dirty != dirty) updateFile(path) { it.copy(dirty = dirty) }
        scheduleCheck(path)
        scheduleSave(path)
    }

    private fun updateFile(path: String, f: (OpenFile) -> OpenFile) =
        _state.update { s -> s.copy(files = s.files.map { if (it.path == path) f(it) else it }) }

    private fun scheduleCheck(path: String) {
        checkJobs[path]?.cancel()
        checkJobs[path] = viewModelScope.launch { delay(CHECK_DELAY_MS); check(path) }
    }

    private suspend fun check(path: String) {
        val b = buffers[path] ?: return
        val lang = Language.forPath(path)
        val text = b.text
        val diagnostics = withContext(dispatchers.default) { Checks.check(text, lang) }
        updateFile(path) { it.copy(diagnostics = diagnostics) }
    }

    private fun scheduleSave(path: String) {
        saveJobs[path]?.cancel()
        saveJobs[path] = viewModelScope.launch {
            if (!settings.settings.first().autoSave) return@launch
            delay(SAVE_DELAY_MS)
            saveJobs.remove(path) // saveNow cancels a pending autosave; this one is no longer pending
            saveNow(path, force = false)
        }
    }

    fun save(path: String, force: Boolean = false) { viewModelScope.launch { saveNow(path, force) } }

    fun saveAll() { viewModelScope.launch { _state.value.files.filter { it.dirty }.forEach { saveNow(it.path, false) } } }

    /** Saves dirty files when autosave is on (screen stopped or left). */
    fun flush() {
        viewModelScope.launch {
            if (!settings.settings.first().autoSave) return@launch
            _state.value.files.filter { it.dirty && !it.conflict }.forEach { saveNow(it.path, false) }
        }
    }

    private suspend fun saveNow(path: String, force: Boolean): Boolean {
        val b = buffers[path] ?: return false
        val pf = project()
        saveJobs[path]?.cancel()
        _state.update { it.copy(saving = true) }
        return try {
            val text = b.text
            val conflict = withContext(dispatchers.io) {
                val onDisk = runCatching { pf.readText(path) }.getOrNull()
                if (!force && onDisk != null && onDisk != b.diskText) true else { pf.writeText(path, text); false }
            }
            if (!conflict) b.diskText = text
            updateFile(path) { it.copy(dirty = b.text != b.diskText, conflict = conflict) }
            _state.update { it.copy(saving = false) }
            !conflict
        } catch (e: CancellationException) {
            _state.update { it.copy(saving = false) }
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(saving = false) }
            _messages.send(CodeMessage.Failed(e.message ?: e.javaClass.simpleName))
            false
        }
    }

    /** Discards edits in [path] and loads it from disk. */
    fun reload(path: String) {
        viewModelScope.launch {
            val pf = project()
            val text = try { withContext(dispatchers.io) { pf.readText(path) } } catch (e: Exception) {
                _messages.send(CodeMessage.Failed(e.message ?: "")); return@launch
            }
            buffers[path] = Buffer(text, text)
            updateFile(path) { it.copy(dirty = false, conflict = false, revision = it.revision + 1) }
            check(path)
        }
    }

    fun close(path: String) {
        saveJobs.remove(path)?.cancel()
        checkJobs.remove(path)?.cancel()
        buffers.remove(path)
        _state.update { s ->
            val i = s.files.indexOfFirst { it.path == path }
            val rest = s.files.filterNot { it.path == path }
            s.copy(files = rest, current = if (s.current == path) rest.getOrNull((i - 1).coerceAtLeast(0))?.path else s.current)
        }
    }

    /** Saves [path] and closes it when the save succeeded. */
    fun saveAndClose(path: String) { viewModelScope.launch { if (saveNow(path, false)) close(path) } }

    /** Saves everything and runs [after] if nothing is left unsaved. */
    fun saveAllThen(after: () -> Unit) {
        viewModelScope.launch {
            var ok = true
            _state.value.files.filter { it.dirty }.forEach { ok = saveNow(it.path, false) && ok }
            if (ok) after()
        }
    }

    fun discardAll() { _state.value.files.filter { it.dirty }.forEach { reload(it.path) } }

    val autoSaveEnabled get() = settings.settings

    companion object {
        const val SAVE_DELAY_MS = 1000L
        const val CHECK_DELAY_MS = 400L

        /** Files the code editor opens (text formats); everything else uses the read-only viewer. */
        fun canOpen(path: String): Boolean {
            val name = path.substringAfterLast('/').lowercase()
            return Language.forPath(path) != Language.Plain ||
                listOf(".txt", ".md", ".pro", ".properties", ".gitignore", ".cfg", ".csv", ".html", ".css", ".js").any { name.endsWith(it) }
        }
    }
}
