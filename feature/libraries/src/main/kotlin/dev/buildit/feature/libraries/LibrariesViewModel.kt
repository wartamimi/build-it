package dev.buildit.feature.libraries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.buildit.core.common.DispatcherProvider
import dev.buildit.core.data.ProjectRepository
import dev.buildit.engine.deps.Coordinate
import dev.buildit.engine.deps.LibraryHit
import dev.buildit.engine.deps.LibrarySearch
import dev.buildit.engine.deps.MavenCache
import dev.buildit.engine.deps.ResolveCancelledException
import dev.buildit.engine.deps.Resolver
import dev.buildit.engine.deps.Versions
import dev.buildit.engine.project.AppCompat
import dev.buildit.engine.project.AppCompatSources
import dev.buildit.engine.project.AppConfig
import dev.buildit.engine.project.LibraryDependency
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** A library that comes with the project's libraries, and which of them need it. */
data class IncludedLibrary(val coordinate: String, val requestedBy: List<String>, val sizeBytes: Long, val direct: Boolean)

/** The version dialog for a library about to be added (or changed). */
data class VersionChoice(val group: String, val artifact: String, val versions: List<String> = emptyList(), val loading: Boolean = true, val error: String? = null) {
    val module get() = "$group:$artifact"
}

sealed interface LibrariesMessage {
    data class Added(val coordinate: String, val total: Int, val bytes: Long) : LibrariesMessage
    data class Removed(val module: String) : LibrariesMessage
    data class GradleFileKept(val path: String) : LibrariesMessage
    data class Failed(val text: String) : LibrariesMessage
    /** AppCompat switched; [updated] screens were rewritten, [needsChange] screens have the user's code. */
    data class AppCompatChanged(val on: Boolean, val updated: List<String>, val needsChange: List<String>, val keptFiles: List<String>) : LibrariesMessage
}

data class LibrariesUiState(
    val loading: Boolean = true,
    val dependencies: List<LibraryDependency> = emptyList(),
    /** The AppCompat and Material switch ([AppCompat]). */
    val appCompat: Boolean = false,
    /** Every library the build will use (direct and transitive), once resolved. */
    val included: List<IncludedLibrary> = emptyList(),
    val resolved: Boolean = false,
    val query: String = "",
    val results: List<LibraryHit> = LibrarySearch.popular,
    val searching: Boolean = false,
    val searchErrors: List<String> = emptyList(),
    /** Set while resolving/downloading: the library being processed. */
    val progress: String? = null,
    val problems: List<String> = emptyList(),
    val choice: VersionChoice? = null,
    val message: LibrariesMessage? = null,
)

/**
 * Library Manager for one project: search Google Maven and Maven Central, add a library at a
 * chosen version (it and everything it needs are downloaded and checked before the project is
 * changed), turn libraries on and off, remove them, and see every library the build includes.
 */
class LibrariesViewModel(
    private val projectId: String,
    private val repository: ProjectRepository,
    private val maven: MavenCache,
    private val dispatchers: DispatcherProvider,
    private val search: LibrarySearch = LibrarySearch(),
) : ViewModel() {
    private val _state = MutableStateFlow(LibrariesUiState())
    val state: StateFlow<LibrariesUiState> = _state.asStateFlow()
    private val cancel = AtomicBoolean(false)
    private var searchJob: Job? = null

    init { load() }

    fun load() {
        viewModelScope.launch {
            val app = try { repository.open(projectId).manifest.app } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = LibrariesMessage.Failed(e.message ?: e.javaClass.simpleName)) }; return@launch
            }
            val deps = app.dependencies
            _state.update { it.copy(loading = false, dependencies = deps, appCompat = app.appCompat) }
            if (deps.any { it.enabled }) resolve(deps, save = false)
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun search() {
        val q = _state.value.query.trim()
        searchJob?.cancel()
        if (q.isEmpty()) { _state.update { it.copy(results = LibrarySearch.popular, searchErrors = emptyList(), searching = false) }; return }
        _state.update { it.copy(searching = true, searchErrors = emptyList()) }
        searchJob = viewModelScope.launch {
            val errors = mutableListOf<String>()
            val hits = withContext(dispatchers.io) { search.search(q, errors = errors) }
            _state.update { it.copy(searching = false, results = hits, searchErrors = errors) }
        }
    }

    /** Opens the version dialog for [group]:[artifact] and loads its published versions. */
    fun choose(group: String, artifact: String) {
        _state.update { it.copy(choice = VersionChoice(group, artifact)) }
        viewModelScope.launch {
            val versions = try {
                withContext(dispatchers.io) {
                    search.takeIf { group.startsWith("androidx.") || group.startsWith("com.google.") }?.googleGroup(group)?.get(artifact)
                        ?: maven.versions(group, artifact)
                }
            } catch (e: Exception) {
                _state.update { s -> s.copy(choice = s.choice?.copy(loading = false, error = e.message ?: e.javaClass.simpleName)) }; return@launch
            }
            _state.update { s ->
                val c = s.choice?.takeIf { it.module == "$group:$artifact" } ?: return@update s
                s.copy(choice = c.copy(versions = versions.sortedWith(Versions).reversed(), loading = false, error = if (versions.isEmpty()) "no versions" else null))
            }
        }
    }

    fun dismissChoice() = _state.update { it.copy(choice = null) }

    fun dismissMessage() = _state.update { it.copy(message = null, problems = emptyList()) }

    /** Adds (or changes the version of) a library after resolving and downloading everything it needs. */
    fun add(group: String, artifact: String, version: String) {
        val coordinate = "$group:$artifact:$version"
        if (Coordinate.parse(coordinate) == null) return
        val deps = _state.value.dependencies.filter { it.module != "$group:$artifact" } + LibraryDependency(coordinate)
        _state.update { it.copy(choice = null) }
        resolve(deps, save = true, added = coordinate)
    }

    fun remove(module: String) {
        val deps = _state.value.dependencies.filter { it.module != module }
        viewModelScope.launch {
            if (save(deps)) {
                _state.update { it.copy(message = LibrariesMessage.Removed(module)) }
                resolve(deps, save = false)
            }
        }
    }

    fun setEnabled(module: String, enabled: Boolean) {
        val deps = _state.value.dependencies.map { if (it.module == module) it.copy(enabled = enabled) else it }
        if (enabled) resolve(deps, save = true) else viewModelScope.launch { if (save(deps)) resolve(deps, save = false) }
    }

    /**
     * Turns AppCompat and Material on or off. On: the libraries are downloaded and checked first,
     * and nothing changes if that fails. Screens still as Build It created them switch base class.
     */
    fun setAppCompat(on: Boolean) {
        val s = _state.value
        if (s.progress != null || on == s.appCompat) return
        val deps = AppCompat.set(AppConfig("x", dependencies = s.dependencies), on).dependencies
        if (on) resolve(deps, save = true, appCompat = true)
        else viewModelScope.launch { if (save(deps, appCompat = false)) resolve(deps, save = false) }
    }

    /** Downloads anything missing (e.g. after the cache was cleared) and refreshes the list. */
    fun refresh() = resolve(_state.value.dependencies, save = false)

    fun cancel() = cancel.set(true)

    override fun onCleared() = cancel.set(true)

    private fun resolve(deps: List<LibraryDependency>, save: Boolean, added: String? = null, appCompat: Boolean? = null) {
        if (_state.value.progress != null) return
        cancel.set(false)
        val roots = deps.filter { it.enabled }.mapNotNull { Coordinate.parse(it.coordinate) }
        if (roots.isEmpty()) {
            viewModelScope.launch {
                if (!save || save(deps, appCompat)) _state.update { it.copy(included = emptyList(), resolved = true, problems = emptyList()) }
            }
            return
        }
        _state.update { it.copy(progress = roots.first().toString(), problems = emptyList()) }
        viewModelScope.launch {
            val resolution = try {
                withContext(dispatchers.io) {
                    Resolver(maven).resolve(roots, progress = { p -> _state.update { it.copy(progress = p) } }, isCancelled = cancel::get)
                }
            } catch (_: ResolveCancelledException) {
                _state.update { it.copy(progress = null) }; return@launch
            } catch (e: Exception) {
                _state.update { it.copy(progress = null, problems = listOf(e.message ?: e.javaClass.simpleName)) }; return@launch
            }
            if (!resolution.ok) {
                // Nothing is changed in the project when a library cannot be fully downloaded.
                _state.update { it.copy(progress = null, problems = resolution.problems) }
                return@launch
            }
            val direct = roots.map { it.module }.toSet()
            val included = resolution.artifacts.map { a ->
                IncludedLibrary(a.coordinate.toString(), a.requestedBy.filter { it != "project" }.sorted(), a.file.length(), a.coordinate.module in direct)
            }
            if (save && !save(deps, appCompat)) { _state.update { it.copy(progress = null) }; return@launch }
            _state.update {
                it.copy(
                    progress = null, included = included, resolved = true,
                    message = added?.let { c -> LibrariesMessage.Added(c, included.size, included.sumOf { l -> l.sizeBytes }) } ?: it.message,
                )
            }
        }
    }

    /** Writes the list (and the AppCompat switch, when given) to the project: buildit.json, app/build.gradle.kts, themes and manifest. */
    private suspend fun save(deps: List<LibraryDependency>, appCompat: Boolean? = null): Boolean = try {
        val project = repository.open(projectId)
        val app = project.manifest.app.copy(dependencies = deps, appCompat = appCompat ?: project.manifest.app.appCompat)
        val result = repository.saveSettings(projectId, project.manifest.copy(app = app))
        val sources = if (appCompat != null) withContext(dispatchers.io) { AppCompatSources.sync(project.directory, result.manifest) } else null
        _state.update { s ->
            s.copy(
                dependencies = result.manifest.app.dependencies,
                appCompat = result.manifest.app.appCompat,
                message = sources?.let { LibrariesMessage.AppCompatChanged(app.appCompat, it.updated, it.needsChange, result.conflicts) }
                    ?: result.conflicts.firstOrNull { it.endsWith("build.gradle.kts") }?.let { LibrariesMessage.GradleFileKept(it) } ?: s.message,
            )
        }
        true
    } catch (e: Exception) {
        _state.update { it.copy(message = LibrariesMessage.Failed(e.message ?: e.javaClass.simpleName)) }
        false
    }
}
