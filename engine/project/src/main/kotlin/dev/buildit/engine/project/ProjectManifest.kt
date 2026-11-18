package dev.buildit.engine.project

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Root document of a Build It project, stored as `buildit.json` in the project directory.
 *
 * The schema is versioned by [formatVersion]; see docs/PROJECT_FORMAT.md. Any change to this class
 * that is not purely additive with a default value requires a new format version and a
 * [FormatMigration].
 */
@Serializable
data class ProjectManifest(
    val formatVersion: Int = ProjectFormat.CURRENT_VERSION,
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val app: AppConfig,
    val modules: List<ModuleConfig> = listOf(ModuleConfig.defaultApp()),
)

@Serializable
data class AppConfig(
    val packageName: String,
    val versionCode: Int = 1,
    val versionName: String = "1.0",
    val minSdk: Int = 24,
    val targetSdk: Int = 36,
    val compileSdk: Int = 36,
    val language: SourceLanguage = SourceLanguage.Java,
    /** Source package of generated R/BuildConfig classes. Null means "same as [packageName]". */
    val namespace: String? = null,
) {
    val effectiveNamespace: String get() = namespace ?: packageName
}

@Serializable
enum class SourceLanguage {
    @SerialName("java") Java,
    @SerialName("kotlin") Kotlin,
}

@Serializable
data class ModuleConfig(
    val name: String,
    val type: ModuleType,
    val activities: List<ActivityConfig> = emptyList(),
) {
    companion object {
        fun defaultApp() = ModuleConfig(
            name = "app",
            type = ModuleType.Application,
            activities = listOf(ActivityConfig(name = "MainActivity", layout = "activity_main", launcher = true)),
        )
    }
}

@Serializable
enum class ModuleType {
    @SerialName("application") Application,
    @SerialName("library") Library,
}

@Serializable
data class ActivityConfig(
    val name: String,
    val layout: String,
    val launcher: Boolean = false,
)

object ProjectFormat {
    const val CURRENT_VERSION = 1
    const val MANIFEST_FILE = "buildit.json"
    const val INTERNAL_DIR = ".buildit"
    const val BACKUP_DIR = ".buildit/backups"
}
