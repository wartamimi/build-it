package dev.buildit.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.buildit.core.common.DispatcherProvider
import dev.buildit.core.data.ProjectRepository
import dev.buildit.engine.project.AssetException
import dev.buildit.engine.project.AssetKind
import dev.buildit.engine.project.ProjectAsset
import dev.buildit.engine.project.ProjectAssets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A picked file waiting for its resource name. */
data class PendingImport(val kind: AssetKind, val displayName: String, val extension: String, val bytes: ByteArray, val name: String, val error: String? = null)

data class AssetsUiState(
    val loading: Boolean = true,
    val assets: Map<AssetKind, List<ProjectAsset>> = emptyMap(),
    val pending: PendingImport? = null,
    /** Error code from [AssetException] (or a message) for the last failed operation. */
    val error: String? = null,
)

/**
 * Images (res/drawable), sounds (res/raw) and fonts (res/font) of a project: import picked files
 * under a valid resource name, rename and delete. Nothing is ever replaced silently.
 */
class AssetsViewModel(
    private val projectId: String,
    private val repository: ProjectRepository,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(AssetsUiState())
    val state: StateFlow<AssetsUiState> = _state.asStateFlow()
    private var store: ProjectAssets? = null

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        try {
            val s = store ?: ProjectAssets(repository.open(projectId).directory).also { store = it }
            val all = withContext(dispatchers.io) { AssetKind.entries.associateWith { s.list(it) } }
            _state.update { it.copy(loading = false, assets = all) }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
        }
    }

    /** A file was picked: ask for its name (suggested from the file name). */
    fun picked(kind: AssetKind, displayName: String, bytes: ByteArray) {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext !in kind.extensions) { _state.update { it.copy(error = "unsupported") }; return }
        _state.update { it.copy(pending = PendingImport(kind, displayName, ext, bytes, ProjectAssets.suggestName(displayName))) }
    }

    fun setPendingName(name: String) = _state.update { s -> s.copy(pending = s.pending?.copy(name = name, error = null)) }

    fun cancelImport() = _state.update { it.copy(pending = null) }

    fun confirmImport() {
        val p = _state.value.pending ?: return
        val s = store ?: return
        viewModelScope.launch {
            try {
                withContext(dispatchers.io) { s.import(p.kind, p.name, p.extension, p.bytes) }
                _state.update { it.copy(pending = null) }
                refresh()
            } catch (e: AssetException) {
                _state.update { st -> st.copy(pending = st.pending?.copy(error = e.code)) }
            }
        }
    }

    fun rename(asset: ProjectAsset, newName: String) = op { it.rename(asset, newName) }

    fun delete(asset: ProjectAsset) = op { it.delete(asset) }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun op(block: (ProjectAssets) -> Unit) {
        val s = store ?: return
        viewModelScope.launch {
            try {
                withContext(dispatchers.io) { block(s) }
            } catch (e: AssetException) {
                _state.update { it.copy(error = e.code) }
            }
            refresh()
        }
    }
}
