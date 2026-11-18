package dev.buildit.studio

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import dev.buildit.core.common.DefaultDispatchers
import dev.buildit.core.common.DispatcherProvider
import dev.buildit.core.data.DataStoreSettingsRepository
import dev.buildit.core.data.DefaultProjectRepository
import dev.buildit.core.data.FileWorkspaceInfo
import dev.buildit.core.data.ProjectRepository
import dev.buildit.core.data.SettingsRepository
import dev.buildit.core.data.WorkspaceInfo
import dev.buildit.core.database.BuildItDatabase
import dev.buildit.engine.project.ProjectStore
import java.io.File

private val Context.settingsStore by preferencesDataStore(name = "settings")

/**
 * Manual dependency container. Build It deliberately avoids a DI framework: the graph is small,
 * explicit wiring is easy to read, and it keeps startup fast.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val dispatchers: DispatcherProvider = DefaultDispatchers
    val workspaceDir: File = File(appContext.filesDir, "workspace")

    private val database: BuildItDatabase by lazy { BuildItDatabase.create(appContext) }
    private val projectStore: ProjectStore by lazy { ProjectStore(workspaceDir) }

    val settingsRepository: SettingsRepository by lazy { DataStoreSettingsRepository(appContext.settingsStore) }
    val projectRepository: ProjectRepository by lazy { DefaultProjectRepository(projectStore, database.projectDao(), dispatchers) }
    val workspaceInfo: WorkspaceInfo by lazy { FileWorkspaceInfo(workspaceDir, dispatchers) }

    val appVersion: String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull() ?: "?"
}
