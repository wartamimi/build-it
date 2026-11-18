package dev.buildit.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.buildit.core.common.DispatcherProvider
import dev.buildit.core.data.ProjectRepository
import dev.buildit.engine.ai.AiClient
import dev.buildit.engine.ai.AiClients
import dev.buildit.engine.ai.AiConfig
import dev.buildit.engine.ai.AiException
import dev.buildit.engine.ai.AiSettingsStore
import dev.buildit.engine.ai.ApplyResult
import dev.buildit.engine.ai.ChangeSet
import dev.buildit.engine.ai.ChatMessage
import dev.buildit.engine.ai.ChatRole
import dev.buildit.engine.ai.ContextFile
import dev.buildit.engine.ai.FileChange
import dev.buildit.engine.ai.Prompts
import dev.buildit.engine.ai.ProposalParser
import dev.buildit.engine.ai.ProviderKind
import dev.buildit.engine.ai.SetupProblem
import dev.buildit.engine.ai.StopKind
import dev.buildit.engine.project.ProjectFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class ChangeStatus { Pending, Applied, Discarded, Stale, Failed }

data class ChangeItem(val change: FileChange, val status: ChangeStatus = ChangeStatus.Pending, val detail: String? = null)

sealed interface ChatItem {
    val id: Long
    data class User(override val id: Long, val text: String, val files: List<String>, val withProblems: Int) : ChatItem
    data class Assistant(override val id: Long, val prose: String, val changes: List<ChangeItem>, val stop: StopKind, val outputTokens: Long?) : ChatItem
    data class Failure(override val id: Long, val kind: AiException.Kind, val message: String) : ChatItem
}

data class SetupState(val config: AiConfig, val hasKey: Boolean) {
    val problem: SetupProblem? get() = config.problem(hasKey)
}

data class AiUiState(
    val setup: SetupState? = null,
    val editingSetup: Boolean = false,
    val items: List<ChatItem> = emptyList(),
    val streaming: String? = null,
    val running: Boolean = false,
    val input: String = "",
    val contextFiles: List<String> = emptyList(),
    val includeProblems: Boolean = true,
    val projectFiles: List<String> = emptyList(),
)

/**
 * The assistant for one project. The user picks which files the model sees; every file the model
 * wants to write comes back as a [FileChange] that is applied only when the user taps Apply, and
 * only if the file has not changed since the diff was made.
 */
class AiViewModel(
    private val projectId: String,
    private val repository: ProjectRepository,
    private val settings: AiSettingsStore,
    private val dispatchers: DispatcherProvider,
    /** The last build's problems, as text lines. */
    private val buildProblems: () -> List<String>,
    /** Called with the paths written by Apply, so open editors reload them. */
    private val onFilesWritten: (List<String>) -> Unit,
    private val clientFactory: (AiConfig, String?) -> AiClient = AiClients::create,
) : ViewModel() {
    private val _state = MutableStateFlow(AiUiState())
    val state: StateFlow<AiUiState> = _state.asStateFlow()
    private val cancel = AtomicBoolean(false)
    /** What was actually sent and received, for the next request. */
    private val history = mutableListOf<ChatMessage>()
    private var nextId = 1L
    private var projectDir: File? = null

    init {
        viewModelScope.launch {
            val setup = withContext(dispatchers.io) { loadSetup() }
            _state.update { it.copy(setup = setup, editingSetup = setup.problem != null) }
            refreshFiles()
        }
    }

    private fun loadSetup(): SetupState {
        val c = settings.load()
        return SetupState(c, settings.apiKey(c.provider) != null)
    }

    val buildProblemCount: Int get() = buildProblems().size

    fun editSetup(open: Boolean) = _state.update { it.copy(editingSetup = open) }

    /** Saves provider settings; a null [apiKey] keeps the stored key, an empty one removes it. */
    fun saveSetup(config: AiConfig, apiKey: String?) {
        viewModelScope.launch {
            val setup = withContext(dispatchers.io) {
                settings.save(config)
                if (apiKey != null) settings.setApiKey(config.provider, apiKey)
                loadSetup()
            }
            _state.update { it.copy(setup = setup, editingSetup = setup.problem != null) }
        }
    }

    fun hasStoredKey(provider: ProviderKind): Boolean = settings.apiKey(provider) != null

    fun onInput(text: String) = _state.update { it.copy(input = text) }

    fun setIncludeProblems(on: Boolean) = _state.update { it.copy(includeProblems = on) }

    fun toggleContext(path: String) = _state.update { s ->
        s.copy(contextFiles = if (path in s.contextFiles) s.contextFiles - path else s.contextFiles + path)
    }

    /** Puts [text] in the composer (for example a crash from the console). */
    fun prefill(text: String, files: List<String> = emptyList()) = _state.update { s ->
        s.copy(input = text, contextFiles = (s.contextFiles + files.filter { it in s.projectFiles }).distinct())
    }

    fun refreshFiles() {
        viewModelScope.launch {
            val list = try {
                val dir = dir()
                withContext(dispatchers.io) { textFiles(dir) }
            } catch (_: Exception) { return@launch }
            _state.update { s -> s.copy(projectFiles = list, contextFiles = s.contextFiles.filter { it in list }) }
        }
    }

    fun newChat() {
        if (_state.value.running) return
        history.clear()
        _state.update { it.copy(items = emptyList(), streaming = null) }
    }

    fun stop() = cancel.set(true)

    override fun onCleared() = cancel.set(true)

    fun send() {
        val s = _state.value
        val question = s.input.trim()
        val setup = s.setup ?: return
        if (question.isEmpty() || s.running || setup.problem != null) return
        cancel.set(false)
        val problems = if (s.includeProblems) buildProblems() else emptyList()
        val userItem = ChatItem.User(nextId++, question, s.contextFiles, problems.size)
        _state.update { it.copy(items = it.items + userItem, input = "", running = true, streaming = "") }
        viewModelScope.launch {
            try {
                val dir = dir()
                val sent = withContext(dispatchers.io) {
                    val pf = ProjectFiles(dir)
                    s.contextFiles.mapNotNull { p -> runCatching { ContextFile(p, pf.readText(p, MAX_CONTEXT_FILE)) }.getOrNull() }
                }
                val project = repository.open(projectId)
                val system = Prompts.system(project.manifest.name, project.manifest.app)
                val turn = ChatMessage(ChatRole.User, Prompts.userTurn(question, sent, problems))
                val messages = (history + turn).takeLast(MAX_HISTORY).dropWhile { it.role != ChatRole.User }
                val key = withContext(dispatchers.io) { settings.apiKey(setup.config.provider) }
                val reply = withContext(dispatchers.io) {
                    clientFactory(setup.config, key).stream(system, messages, { piece ->
                        _state.update { st -> st.copy(streaming = (st.streaming ?: "") + piece) }
                    }, cancel::get)
                }
                history += turn
                history += ChatMessage(ChatRole.Assistant, reply.text)
                val parsed = ProposalParser.parse(reply.text)
                val changes = withContext(dispatchers.io) { ChangeSet(dir).prepare(parsed.files, sent) }
                    .filterNot { it.unchanged }
                    .map { ChangeItem(it) }
                _state.update {
                    it.copy(items = it.items + ChatItem.Assistant(nextId++, parsed.prose, changes, reply.stop, reply.outputTokens), running = false, streaming = null)
                }
            } catch (e: AiException) {
                fail(e.kind, e.message.orEmpty())
            } catch (e: Exception) {
                fail(AiException.Kind.Server, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun fail(kind: AiException.Kind, message: String) {
        _state.update { st ->
            // Keep what streamed before a cancel so the user can still read it.
            val partial = st.streaming?.takeIf { it.isNotBlank() && kind == AiException.Kind.Cancelled }
            val items = st.items + listOfNotNull(
                partial?.let { ChatItem.Assistant(nextId++, ProposalParser.parse(it).prose, emptyList(), StopKind.Other, null) },
                ChatItem.Failure(nextId++, kind, message),
            )
            st.copy(items = items, running = false, streaming = null)
        }
    }

    fun apply(itemId: Long, path: String) = applyAll(itemId, setOf(path))

    /** Applies the pending changes of one reply ([only] limits it to some paths). */
    fun applyAll(itemId: Long, only: Set<String>? = null) {
        val item = _state.value.items.firstOrNull { it.id == itemId } as? ChatItem.Assistant ?: return
        viewModelScope.launch {
            val dir = dir()
            val written = mutableListOf<String>()
            val updated = withContext(dispatchers.io) {
                val cs = ChangeSet(dir)
                item.changes.map { c ->
                    if (c.status != ChangeStatus.Pending || c.change.blocked != null || (only != null && c.change.path !in only)) c
                    else when (val r = cs.apply(c.change)) {
                        ApplyResult.Applied -> { written += c.change.path; c.copy(status = ChangeStatus.Applied) }
                        ApplyResult.Stale -> c.copy(status = ChangeStatus.Stale)
                        is ApplyResult.Failed -> c.copy(status = ChangeStatus.Failed, detail = r.message)
                    }
                }
            }
            replaceChanges(itemId, updated)
            if (written.isNotEmpty()) { onFilesWritten(written); refreshFiles() }
        }
    }

    fun discard(itemId: Long, path: String) {
        val item = _state.value.items.firstOrNull { it.id == itemId } as? ChatItem.Assistant ?: return
        replaceChanges(itemId, item.changes.map { if (it.change.path == path && it.status == ChangeStatus.Pending) it.copy(status = ChangeStatus.Discarded) else it })
    }

    private fun replaceChanges(itemId: Long, changes: List<ChangeItem>) = _state.update { s ->
        s.copy(items = s.items.map { if (it.id == itemId && it is ChatItem.Assistant) it.copy(changes = changes) else it })
    }

    private suspend fun dir(): File = projectDir ?: repository.open(projectId).directory.also { projectDir = it }

    companion object {
        const val MAX_HISTORY = 12
        const val MAX_CONTEXT_FILE = 256L * 1024

        private val textExtensions = setOf("java", "kt", "xml", "gradle", "kts", "json", "properties", "pro", "txt", "md")

        /** Project source files the user can attach, excluding build output and Build It metadata. */
        fun textFiles(dir: File): List<String> = dir.walkTopDown()
            .onEnter { d -> d == dir || (d.name != "build" && !d.name.startsWith(".")) }
            .filter { it.isFile && it.extension.lowercase() in textExtensions && it.length() <= MAX_CONTEXT_FILE && it.name != "buildit.json" }
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .sorted()
            .toList()
    }
}
