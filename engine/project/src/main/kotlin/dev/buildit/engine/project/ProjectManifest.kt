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
    /** Maven libraries (`group:artifact:version`) the app uses, managed in the Libraries tab. */
    val dependencies: List<LibraryDependency> = emptyList(),
    /** Manifest permissions (`android.permission.INTERNET`, ...); components add the ones they need. */
    val permissions: List<String> = emptyList(),
    /** AndroidX AppCompat and Material Components on (Library Manager switch): see [AppCompat]. */
    val appCompat: Boolean = false,
    /** Theme colours chosen when the project was created (null: the platform theme's own colours). */
    val colors: ThemeColors? = null,
) {
    val effectiveNamespace: String get() = namespace ?: packageName
}

/** The app's theme colours, `#AARRGGBB`, written to res/values/colors.xml and used by the theme. */
@Serializable
data class ThemeColors(
    val primary: String = "#FF3F51B5",
    val primaryDark: String = "#FF303F9F",
    val accent: String = "#FFFF4081",
)

/** Settings chosen before a project is created (like Sketchware's new-project screen). */
data class NewProjectOptions(
    val versionCode: Int = 1,
    val versionName: String = "1.0",
    val minSdk: Int = 24,
    val appCompat: Boolean = false,
    val colors: ThemeColors? = null,
)

/** A library added to the project. Disabled libraries stay listed but are not built or bundled. */
@Serializable
data class LibraryDependency(
    val coordinate: String,
    val enabled: Boolean = true,
) {
    /** `group:artifact` without the version. */
    val module: String get() = coordinate.substringBeforeLast(':')
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
    val orientation: ScreenOrientation = ScreenOrientation.Unspecified,
    val keyboard: KeyboardMode = KeyboardMode.Default,
    val window: WindowStyle = WindowStyle.Default,
)

/** android:screenOrientation of a screen ([manifestValue] null: attribute left out). */
@Serializable
enum class ScreenOrientation(val manifestValue: String?) {
    @SerialName("unspecified") Unspecified(null),
    @SerialName("portrait") Portrait("portrait"),
    @SerialName("landscape") Landscape("landscape"),
    @SerialName("sensor") Sensor("fullSensor"),
}

/** android:windowSoftInputMode of a screen. */
@Serializable
enum class KeyboardMode(val manifestValue: String?) {
    @SerialName("default") Default(null),
    @SerialName("resize") Resize("adjustResize"),
    @SerialName("pan") Pan("adjustPan"),
    @SerialName("hidden") HiddenOnStart("stateHidden"),
    @SerialName("visible") VisibleOnStart("stateVisible|adjustResize"),
}

/**
 * Window of a screen: the app theme with its action bar, without it, or fullscreen. [style] is
 * the style Build It writes to themes.xml; [frameworkTheme] is used when themes.xml was edited by
 * hand and has no such style.
 */
@Serializable
enum class WindowStyle(val style: String?, val frameworkTheme: String?) {
    @SerialName("default") Default(null, null),
    @SerialName("no_action_bar") NoActionBar("AppTheme.NoActionBar", "@android:style/Theme.Material.Light.NoActionBar"),
    @SerialName("fullscreen") Fullscreen("AppTheme.Fullscreen", "@android:style/Theme.Material.Light.NoActionBar.Fullscreen"),
}

object ProjectFormat {
    const val CURRENT_VERSION = 1
    const val MANIFEST_FILE = "buildit.json"
    const val INTERNAL_DIR = ".buildit"
    const val BACKUP_DIR = ".buildit/backups"
}
