package dev.buildit.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.buildit.core.common.DispatcherProvider
import dev.buildit.core.data.OpenProject
import dev.buildit.core.data.ProjectRepository
import dev.buildit.engine.project.AppConfig
import dev.buildit.engine.project.ProjectEntry
import dev.buildit.engine.project.ProjectFiles
import dev.buildit.engine.project.ProjectManifest
import dev.buildit.engine.project.ProjectValidation
import dev.buildit.engine.project.ValidationIssue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/** Editable copy of the project settings; strings so partially typed numbers are allowed. */
data class SettingsDraft(
    val name: String,
    val packageName: String,
    val versionCode: String,
    val versionName: String,
    val minSdk: String,
    val targetSdk: String,
    val compileSdk: String,
    /** Manifest permissions, full names. */
    val permissions: List<String> = emptyList(),
) {
    /** The manifest with these settings; everything the form does not show (libraries, AppCompat, screens) is kept. */
    fun toManifest(base: ProjectManifest): ProjectManifest? {
        val app = base.app.copy(
            packageName = packageName.trim(),
            versionCode = versionCode.toIntOrNull() ?: return null,
            versionName = versionName.trim(),
            minSdk = minSdk.toIntOrNull() ?: return null,
            targetSdk = targetSdk.toIntOrNull() ?: return null,
            compileSdk = compileSdk.toIntOrNull() ?: return null,
            namespace = base.app.effectiveNamespace,
            permissions = permissions.distinct().sorted(),
        )
        return base.copy(name = name.trim(), app = app)
    }

    companion object {
        fun from(m: ProjectManifest) = SettingsDraft(
            m.name, m.app.packageName, m.app.versionCode.toString(), m.app.versionName,
            m.app.minSdk.toString(), m.app.targetSdk.toString(), m.app.compileSdk.toString(),
            m.app.permissions,
        )

        /** Permissions offered as switches; others can be typed in. */
        val commonPermissions = listOf(
            "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO", "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_AUDIO", "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.VIBRATE",
            "android.permission.POST_NOTIFICATIONS", "android.permission.WAKE_LOCK", "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.FOREGROUND_SERVICE", "android.permission.CALL_PHONE", "android.permission.READ_CONTACTS",
            "android.permission.SEND_SMS", "android.permission.BLUETOOTH_CONNECT",
        )

        private val permissionName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        fun isPermissionName(s: String) = permissionName.matches(s)
    }
}

data class FilesState(
    val path: String = "",
    val entries: List<ProjectEntry> = emptyList(),
    val openFile: String? = null,
    val openFileText: String? = null,
)

data class WorkspaceUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val project: OpenProject? = null,
    val draft: SettingsDraft? = null,
    val issues: List<ValidationIssue> = emptyList(),
    val saving: Boolean = false,
    val pendingConflicts: List<String> = emptyList(),
    val files: FilesState = FilesState(),
) {
    val dirty: Boolean get() = project != null && draft != null && draft != SettingsDraft.from(project.manifest)
}

sealed interface WorkspaceMessage {
    data object Saved : WorkspaceMessage
    data class Failed(val detail: String) : WorkspaceMessage
}

class WorkspaceViewModel(
    private val projectId: String,
    private val repository: ProjectRepository,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceUiState())
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()
    private val _messages = Channel<WorkspaceMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private var files: ProjectFiles? = null

    init { load() }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        try {
            val p = repository.open(projectId)
            files = ProjectFiles(p.directory)
            _state.update { it.copy(loading = false, project = p, draft = SettingsDraft.from(p.manifest)) }
            browse("")
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
        }
    }


    fun editDraft(transform: (SettingsDraft) -> SettingsDraft) = _state.update { s ->
        val d = s.draft?.let(transform)
        s.copy(draft = d, issues = d?.let { validate(it, s.project!!.manifest) }.orEmpty())
    }

    fun discardDraft() = _state.update { s -> s.copy(draft = s.project?.manifest?.let(SettingsDraft::from), issues = emptyList()) }

    private fun validate(d: SettingsDraft, base: ProjectManifest): List<ValidationIssue> {
        val m = d.toManifest(base) ?: return listOf(ValidationIssue("number", "not_a_number"))
        return ProjectValidation.validate(m)
    }

    fun save(force: Boolean = false) {
        val s = _state.value
        val project = s.project ?: return
        val manifest = s.draft?.toManifest(project.manifest) ?: return
        if (validate(s.draft, project.manifest).isNotEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            try {
                val result = repository.saveSettings(projectId, manifest, force)
                _state.update {
                    it.copy(
                        saving = false,
                        project = project.copy(manifest = result.manifest, summary = project.summary.copy(name = result.manifest.name, packageName = result.manifest.app.packageName)),
                        draft = SettingsDraft.from(result.manifest),
                        pendingConflicts = result.conflicts,
                    )
                }
                if (result.conflicts.isEmpty()) _messages.send(WorkspaceMessage.Saved)
                refreshFiles()
            } catch (e: Exception) {
                _state.update { it.copy(saving = false) }
                _messages.send(WorkspaceMessage.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun dismissConflicts() = _state.update { it.copy(pendingConflicts = emptyList()) }

    // ---- Files ----

    fun browse(path: String) = fileOp {
        val entries = files!!.list(path)
        _state.update { it.copy(files = it.files.copy(path = path, entries = entries)) }
    }

    fun up() {
        val p = _state.value.files.path
        if (p.isNotEmpty()) browse(p.substringBeforeLast('/', ""))
    }

    fun openFile(entry: ProjectEntry) = fileOp {
        val text = files!!.readText(entry.path)
        _state.update { it.copy(files = it.files.copy(openFile = entry.path, openFileText = text)) }
    }

    fun closeFile() = _state.update { it.copy(files = it.files.copy(openFile = null, openFileText = null)) }

    fun createFile(name: String) = fileOp { files!!.createFile(_state.value.files.path, name); refreshFilesNow() }
    fun createFolder(name: String) = fileOp { files!!.createFolder(_state.value.files.path, name); refreshFilesNow() }
    fun rename(entry: ProjectEntry, newName: String) = fileOp { files!!.rename(entry.path, newName); refreshFilesNow() }
    fun delete(entry: ProjectEntry) = fileOp { files!!.delete(entry.path); refreshFilesNow() }
    fun isProtected(entry: ProjectEntry) = files?.isProtected(entry.path) ?: true

    private fun refreshFiles() = browse(_state.value.files.path)

    private fun refreshFilesNow() {
        val entries = files!!.list(_state.value.files.path)
        _state.update { it.copy(files = it.files.copy(entries = entries)) }
    }

    private fun fileOp(block: () -> Unit) = viewModelScope.launch {
        try {
            withContext(dispatchers.io) { block() }
        } catch (e: Exception) {
            _messages.send(WorkspaceMessage.Failed(e.message ?: e.javaClass.simpleName))
        }
    }
}
