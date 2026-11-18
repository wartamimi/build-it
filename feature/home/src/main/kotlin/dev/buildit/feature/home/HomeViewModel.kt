package dev.buildit.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.buildit.core.data.ProjectRepository
import dev.buildit.core.data.StorageUsage
import dev.buildit.core.data.WorkspaceInfo
import dev.buildit.core.model.ProjectSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val recent: List<ProjectSummary> = emptyList(),
    val projectCount: Int = 0,
    val storage: StorageUsage? = null,
)

class HomeViewModel(
    projects: ProjectRepository,
    private val workspace: WorkspaceInfo,
) : ViewModel() {
    private val storage = MutableStateFlow<StorageUsage?>(null)

    val state: StateFlow<HomeUiState> = combine(projects.recentProjects(RECENT_LIMIT), projects.projectCount, storage) { recent, count, s ->
        HomeUiState(loading = false, recent = recent, projectCount = count, storage = s)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init { refreshStorage() }

    fun refreshStorage() {
        viewModelScope.launch { storage.value = runCatching { workspace.usage() }.getOrNull() }
    }

    companion object { const val RECENT_LIMIT = 5 }
}
