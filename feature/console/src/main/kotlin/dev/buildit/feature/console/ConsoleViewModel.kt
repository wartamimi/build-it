package dev.buildit.feature.console

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.buildit.core.common.DispatcherProvider
import dev.buildit.core.data.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ConsoleFilter(
    val minLevel: ConsoleLevel = ConsoleLevel.Verbose,
    val query: String = "",
)

data class ConsoleUiState(
    val applicationId: String? = null,
    val entries: List<ConsoleEntry> = emptyList(),
    val total: Int = 0,
    val filter: ConsoleFilter = ConsoleFilter(),
)

/** The console of one project: entries from its debug builds, filtered by level and text. */
class ConsoleViewModel(
    private val projectId: String,
    private val repository: ProjectRepository,
    private val log: ConsoleLog,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val appId = MutableStateFlow<String?>(null)
    private val filter = MutableStateFlow(ConsoleFilter())
    private var projectDir: File? = null

    val state: StateFlow<ConsoleUiState> = combine(log.entries, appId, filter) { all, id, f ->
        val mine = if (id == null) emptyList() else all.filter { it.packageName == id }
        val q = f.query.trim()
        ConsoleUiState(
            applicationId = id,
            total = mine.size,
            filter = f,
            entries = mine.filter { e ->
                levelPasses(e.level, f.minLevel) && (q.isEmpty() || e.text.contains(q, ignoreCase = true) || e.tag.contains(q, ignoreCase = true))
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConsoleUiState())

    init { refresh() }

    /** Re-reads the application id (it can change in Project settings). */
    fun refresh() {
        viewModelScope.launch {
            val p = runCatching { repository.open(projectId) }.getOrNull() ?: return@launch
            projectDir = p.directory
            appId.value = p.manifest.app.packageName
        }
    }

    fun setMinLevel(l: ConsoleLevel) = filter.update { it.copy(minLevel = l) }
    fun setQuery(q: String) = filter.update { it.copy(query = q) }
    fun clear() { appId.value?.let(log::clear) }

    /** The project file and line for [frame], or null when the frame is not in the project's sources. */
    suspend fun resolve(frame: Frame): Pair<String, Int>? {
        val dir = projectDir ?: return null
        return withContext(dispatchers.io) { SourceLinks.resolve(dir, frame) }?.let { it to frame.line }
    }

    companion object {
        /** Crashes and events always show; log lines show at or above [min]. */
        fun levelPasses(level: ConsoleLevel, min: ConsoleLevel): Boolean =
            level == ConsoleLevel.Crash || level == ConsoleLevel.Event || level.ordinal >= min.ordinal
    }
}
