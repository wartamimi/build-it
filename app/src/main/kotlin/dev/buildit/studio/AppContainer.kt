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
import dev.buildit.engine.build.Toolchain
import dev.buildit.engine.build.DebugBridge
import dev.buildit.engine.ai.AiSettingsStore
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
    /** Downloaded platforms, accepted licenses, compiler stubs and the debug signing key. */
    val toolchainDir: File = File(appContext.filesDir, "toolchain")
    val toolchain: Toolchain by lazy { Toolchain(toolchainDir) }
    val debugKeystore: File get() = File(toolchainDir, "keys/debug.p12")
    /** Libraries downloaded from Google Maven and Maven Central, shared by all projects. */
    val maven: dev.buildit.engine.deps.MavenCache by lazy { dev.buildit.engine.deps.MavenCache(File(toolchainDir, "maven")) }

    /** AI provider settings; the API key itself is kept encrypted by the Android Keystore. */
    val aiSettings: AiSettingsStore by lazy { AiSettingsStore(File(appContext.filesDir, "ai/config.json"), KeystoreSecretStore(appContext)) }

    /** Debug builds send their logs here (see the console provider in feature:console). */
    val consoleHost = DebugBridge.Host(appContext.packageName, appContext.packageName + ".console")

    private val database: BuildItDatabase by lazy { BuildItDatabase.create(appContext) }
    private val projectStore: ProjectStore by lazy { ProjectStore(workspaceDir) }

    val settingsRepository: SettingsRepository by lazy { DataStoreSettingsRepository(appContext.settingsStore) }
    val projectRepository: ProjectRepository by lazy { DefaultProjectRepository(projectStore, database.projectDao(), dispatchers) }
    val workspaceInfo: WorkspaceInfo by lazy { FileWorkspaceInfo(workspaceDir, dispatchers) }

    val appVersion: String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull() ?: "?"
}
