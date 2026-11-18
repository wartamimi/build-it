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

    /** Blocks, widgets, images and sounds saved once and reused in every project. */
    val collection: dev.buildit.engine.project.CollectionStore by lazy { dev.buildit.engine.project.CollectionStore(File(appContext.filesDir, "collection")) }

    /** Runs the Kotlin compiler (downloaded from Maven Central, converted to DEX once) for Kotlin projects. */
    val kotlinCompiler: dev.buildit.engine.build.KotlinCompilerHost by lazy {
        dev.buildit.feature.build.AndroidKotlinCompilerHost(File(toolchainDir, "kotlin"), androidJar = { toolchain.installedApis().lastOrNull()?.let(toolchain::androidJar) })
    }

    /** AI provider settings; the API key itself is kept encrypted by the Android Keystore. */
    val aiSettings: AiSettingsStore by lazy { AiSettingsStore(File(appContext.filesDir, "ai/config.json"), KeystoreSecretStore(appContext)) }

    /**
     * Release signing keys: keystores in app-private storage (never in a project), passwords
     * encrypted by the Android Keystore in the same store as AI keys.
     */
    val releaseKeys: dev.buildit.engine.build.ReleaseKeyring by lazy {
        val secrets = KeystoreSecretStore(appContext)
        dev.buildit.engine.build.ReleaseKeyring(File(appContext.filesDir, "release-keys"), object : dev.buildit.engine.build.PasswordStore {
            override fun read(name: String) = secrets.read(name)
            override fun write(name: String, value: String) = secrets.write(name, value)
            override fun delete(name: String) = secrets.delete(name)
        })
    }

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
