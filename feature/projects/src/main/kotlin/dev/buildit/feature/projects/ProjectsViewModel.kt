package dev.buildit.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.buildit.core.data.ProjectRepository
import dev.buildit.core.model.ProjectSummary
import dev.buildit.engine.project.ProjectTemplate
import dev.buildit.engine.project.ProjectValidation
import dev.buildit.engine.project.ProjectValidationException
import dev.buildit.engine.project.ValidationIssue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

enum class ProjectSort { Updated, Name, Created }

data class BackupList(val project: ProjectSummary, val files: List<java.io.File>)

data class ProjectsUiState(
    val loading: Boolean = true,
    val projects: List<ProjectSummary> = emptyList(),
    val query: String = "",
    val sort: ProjectSort = ProjectSort.Updated,
    val busy: Boolean = false,
)

/** One-off messages for the snackbar. */
sealed interface ProjectsMessage {
    data class Created(val name: String) : ProjectsMessage
    data class Imported(val name: String) : ProjectsMessage
    data object Exported : ProjectsMessage
    data class BackedUp(val fileName: String) : ProjectsMessage
    data object Deleted : ProjectsMessage
    data object Restored : ProjectsMessage
    data class Failed(val detail: String) : ProjectsMessage
}

class ProjectsViewModel(private val repository: ProjectRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(ProjectSort.Updated)
    private val busy = MutableStateFlow(false)
    private val _messages = Channel<ProjectsMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** Emits the id of a project that should be opened right away (after create/import). */
    private val _openRequests = Channel<String>(Channel.BUFFERED)
    val openRequests = _openRequests.receiveAsFlow()

    val state: StateFlow<ProjectsUiState> = combine(repository.projects, query, sort, busy) { list, q, s, b ->
        val filtered = if (q.isBlank()) list else list.filter {
            it.name.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
        }
        val sorted = when (s) {
            ProjectSort.Updated -> filtered.sortedByDescending { it.updatedAt }
            ProjectSort.Name -> filtered.sortedBy { it.name.lowercase() }
            ProjectSort.Created -> filtered.sortedByDescending { it.createdAt }
        }
        ProjectsUiState(loading = false, projects = sorted, query = q, sort = s, busy = b)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectsUiState())

    fun setQuery(q: String) = query.update { q }
    fun setSort(s: ProjectSort) = sort.update { s }

    fun validateNew(name: String, packageName: String): List<ValidationIssue> =
        ProjectValidation.validateName(name) + ProjectValidation.validatePackageName(packageName)

    fun create(name: String, packageName: String, template: ProjectTemplate) = run {
        val p = repository.create(name, packageName, template)
        _messages.send(ProjectsMessage.Created(p.name))
        _openRequests.send(p.id)
    }

    fun rename(id: String, newName: String) = run { repository.rename(id, newName) }

    fun duplicate(id: String, newName: String) = run { repository.duplicate(id, newName) }

    fun delete(id: String) = run {
        repository.delete(id)
        _messages.send(ProjectsMessage.Deleted)
    }

    fun backup(id: String) = run {
        val f = repository.backup(id)
        _messages.send(ProjectsMessage.BackedUp(f.name))
    }

    /** Backups of one project, newest first, for the restore picker. Null when no picker is open. */
    private val _backups = MutableStateFlow<BackupList?>(null)
    val backups: StateFlow<BackupList?> = _backups

    fun showBackups(project: ProjectSummary) = run {
        _backups.value = BackupList(project, repository.listBackups(project.id))
    }

    fun hideBackups() { _backups.value = null }

    fun restore(id: String, backup: java.io.File) = run {
        _backups.value = null
        repository.restore(id, backup)
        _messages.send(ProjectsMessage.Restored)
    }

    fun import(open: () -> InputStream?) = run {
        val input = open() ?: throw java.io.IOException("Could not open the selected file")
        val p = input.use { repository.import(it) }
        _messages.send(ProjectsMessage.Imported(p.name))
    }

    fun export(id: String, open: () -> OutputStream?) = run {
        val out = open() ?: throw java.io.IOException("Could not write to the selected location")
        out.use { repository.export(id, it) }
        _messages.send(ProjectsMessage.Exported)
    }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            try {
                block()
            } catch (e: ProjectValidationException) {
                _messages.send(ProjectsMessage.Failed(e.issues.joinToString { "${it.field}: ${it.code}" }))
            } catch (e: Exception) {
                _messages.send(ProjectsMessage.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                busy.value = false
            }
        }
    }
}
