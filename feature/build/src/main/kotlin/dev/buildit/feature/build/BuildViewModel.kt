package dev.buildit.feature.build

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.buildit.core.common.DispatcherProvider
import dev.buildit.core.data.ProjectRepository
import dev.buildit.engine.build.BuildCancelledException
import dev.buildit.engine.build.BuildDiagnostic
import dev.buildit.engine.build.BuildEvent
import dev.buildit.engine.build.BuildPipeline
import dev.buildit.engine.build.BuildRequest
import dev.buildit.engine.build.BuildStep
import dev.buildit.engine.build.BuildType
import dev.buildit.engine.build.DebugBridge
import dev.buildit.engine.build.Keys
import dev.buildit.engine.build.PlatformPackage
import dev.buildit.engine.build.Toolchain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** State of the Android platform (android.jar) the project compiles against. */
sealed interface PlatformState {
    data object Checking : PlatformState
    data class Ready(val api: Int) : PlatformState
    data class Missing(val api: Int) : PlatformState
    data class FetchingIndex(val api: Int) : PlatformState
    data class License(val pkg: PlatformPackage, val text: String) : PlatformState
    data class Downloading(val api: Int, val read: Long, val total: Long) : PlatformState
    data class Failed(val api: Int, val message: String) : PlatformState
}

enum class StepState { Pending, Running, Done, Failed }

data class StepRow(val step: BuildStep, val state: StepState = StepState.Pending, val millis: Long = 0)

data class ApkInfo(val file: File, val sizeBytes: Long)

data class BuildUiState(
    val api: Int? = null,
    val platform: PlatformState = PlatformState.Checking,
    val running: Boolean = false,
    val steps: List<StepRow> = emptyList(),
    val diagnostics: List<BuildDiagnostic> = emptyList(),
    val log: List<String> = emptyList(),
    val apk: ApkInfo? = null,
    val failedStep: BuildStep? = null,
    val cancelled: Boolean = false,
)

/**
 * Drives the on-device build for one project: makes sure the project's Android platform is
 * installed (downloading it from Google after the user accepts the SDK license), runs the
 * [BuildPipeline] off the main thread with live step progress, and exposes the result.
 */
class BuildViewModel(
    private val projectId: String,
    private val repository: ProjectRepository,
    private val toolchain: Toolchain,
    private val debugKeystore: File,
    private val dispatchers: DispatcherProvider,
    private val pipeline: BuildPipeline = BuildPipeline(),
    /** Debug builds connect to this console (null leaves the console bridge out). */
    private val console: DebugBridge.Host? = null,
    /** Library cache; projects with libraries need it. */
    private val maven: dev.buildit.engine.deps.MavenCache? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(BuildUiState())
    val state: StateFlow<BuildUiState> = _state.asStateFlow()
    private val cancel = AtomicBoolean(false)

    init { refreshPlatform() }

    /** Re-reads the project's compileSdk (it may have changed in Project settings) unless busy. */
    fun refreshPlatform() {
        val p = _state.value.platform
        if (p !is PlatformState.Checking && p !is PlatformState.Ready && p !is PlatformState.Missing && p !is PlatformState.Failed) return
        viewModelScope.launch {
            val api = try { repository.open(projectId).manifest.app.compileSdk } catch (e: Exception) { return@launch }
            val installed = withContext(dispatchers.io) { toolchain.androidJar(api) != null }
            _state.update { it.copy(api = api, platform = if (installed) PlatformState.Ready(api) else PlatformState.Missing(api)) }
        }
    }

    /** Looks the platform up in Google's index and asks for the license if not yet accepted. */
    fun requestPlatform() {
        val api = _state.value.api ?: return
        _state.update { it.copy(platform = PlatformState.FetchingIndex(api)) }
        viewModelScope.launch {
            try {
                val index = withContext(dispatchers.io) { toolchain.fetchIndex() }
                val pkg = index.platform(api) ?: error("Android API $api is not available from Google's SDK repository")
                val license = pkg.licenseId?.let { index.licenses[it] }
                if (license != null && !toolchain.isLicenseAccepted(pkg.licenseId!!, license)) {
                    _state.update { it.copy(platform = PlatformState.License(pkg, license)) }
                } else download(pkg)
            } catch (e: Exception) {
                _state.update { it.copy(platform = PlatformState.Failed(api, e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    fun acceptLicense() {
        val l = _state.value.platform as? PlatformState.License ?: return
        viewModelScope.launch {
            withContext(dispatchers.io) { toolchain.acceptLicense(l.pkg.licenseId!!, l.text) }
            download(l.pkg)
        }
    }

    fun declineLicense() {
        val l = _state.value.platform as? PlatformState.License ?: return
        _state.update { it.copy(platform = PlatformState.Missing(l.pkg.api)) }
    }

    private fun download(pkg: PlatformPackage) {
        cancel.set(false)
        _state.update { it.copy(platform = PlatformState.Downloading(pkg.api, 0, pkg.size)) }
        viewModelScope.launch {
            try {
                var lastPercent = -1L
                withContext(dispatchers.io) {
                    toolchain.install(pkg, progress = { read, total ->
                        val percent = if (total > 0) read * 100 / total else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            _state.update { it.copy(platform = PlatformState.Downloading(pkg.api, read, total)) }
                        }
                    }, isCancelled = cancel::get)
                }
                _state.update { it.copy(platform = PlatformState.Ready(pkg.api)) }
            } catch (_: BuildCancelledException) {
                _state.update { it.copy(platform = PlatformState.Missing(pkg.api)) }
            } catch (e: Exception) {
                _state.update { it.copy(platform = PlatformState.Failed(pkg.api, e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    fun cancel() = cancel.set(true)

    /** Leaving the project stops a running build or download at its next step boundary. */
    override fun onCleared() = cancel.set(true)

    /** Builds the project; [onSuccess] gets the APK when the build succeeds (e.g. to install it). */
    fun build(type: BuildType = BuildType.Debug, onSuccess: ((File) -> Unit)? = null) {
        if (_state.value.running) return
        cancel.set(false)
        _state.update {
            it.copy(running = true, steps = BuildStep.entries.map(::StepRow), diagnostics = emptyList(), log = emptyList(), apk = null, failedStep = null, cancelled = false)
        }
        viewModelScope.launch {
            try {
                val project = repository.open(projectId)
                val app = project.manifest.app
                val jar = toolchain.androidJar(app.compileSdk)
                if (jar == null) {
                    _state.update { it.copy(running = false, steps = emptyList(), api = app.compileSdk, platform = PlatformState.Missing(app.compileSdk)) }
                    return@launch
                }
                val request = BuildRequest(project.directory, app, jar, type, key = { Keys.debugKey(debugKeystore) }, toolCache = toolchain.cacheDir, console = console, maven = maven)
                val result = withContext(dispatchers.default) { pipeline.run(request, ::onEvent, cancel::get) }
                _state.update { s ->
                    s.copy(
                        running = false,
                        steps = s.steps.map { if (it.step == result.failedStep && !result.cancelled) it.copy(state = StepState.Failed) else if (it.state == StepState.Running) it.copy(state = StepState.Pending) else it },
                        diagnostics = result.diagnostics.sortedBy { it.severity },
                        apk = result.apk?.let { ApkInfo(it, it.length()) },
                        failedStep = result.failedStep.takeIf { !result.success },
                        cancelled = result.cancelled,
                    )
                }
                if (result.success) result.apk?.let { onSuccess?.invoke(it) }
            } catch (e: Exception) {
                _state.update { it.copy(running = false, diagnostics = listOf(BuildDiagnostic(BuildDiagnostic.Severity.Error, e.message ?: e.javaClass.simpleName))) }
            }
        }
    }

    private fun onEvent(e: BuildEvent) {
        _state.update { s ->
            when (e) {
                is BuildEvent.StepStarted -> s.copy(steps = s.steps.map { if (it.step == e.step) it.copy(state = StepState.Running) else it })
                is BuildEvent.StepFinished -> s.copy(steps = s.steps.map { if (it.step == e.step) it.copy(state = StepState.Done, millis = e.millis) else it })
                is BuildEvent.Log -> s.copy(log = s.log + e.text)
            }
        }
    }
}
