package dev.buildit.studio

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BuildItApplication : Application() {
    lateinit var container: AppContainer
        private set

    /** Lives as long as the process; for work that must not be tied to one screen. */
    val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Recovery after unexpected termination: drop half-finished operations and make the
        // project index match what is actually on disk.
        appScope.launch(container.dispatchers.io) {
            runCatching { container.projectRepository.reindex() }
                .onFailure { Log.w("BuildIt", "Workspace scan failed", it) }
        }
    }
}
