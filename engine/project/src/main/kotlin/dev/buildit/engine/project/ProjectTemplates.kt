package dev.buildit.engine.project

import java.io.File

/** A starting point for a new project. [generate] writes real, buildable source files. */
interface ProjectTemplate {
    val id: String
    val language: SourceLanguage
    val module: ModuleConfig
    fun generate(projectDir: File, manifest: ProjectManifest)
}

object ProjectTemplates {
    val EmptyActivity: ProjectTemplate = EmptyActivityTemplate(SourceLanguage.Java)
    val EmptyActivityKotlin: ProjectTemplate = EmptyActivityTemplate(SourceLanguage.Kotlin)
    val all: List<ProjectTemplate> = listOf(EmptyActivity, EmptyActivityKotlin)

    fun byId(id: String): ProjectTemplate? = all.firstOrNull { it.id == id }
}

/**
 * Standard Android project layout (`app/src/main/...`) so a project exported from Build It opens
 * in Android Studio, and so the on-device build engine works from the same files.
 */
private class EmptyActivityTemplate(override val language: SourceLanguage) : ProjectTemplate {
    override val id: String = when (language) {
        SourceLanguage.Java -> "empty_activity_java"
        SourceLanguage.Kotlin -> "empty_activity_kotlin"
    }
    override val module: ModuleConfig = ModuleConfig.defaultApp()

    override fun generate(projectDir: File, manifest: ProjectManifest) {
        val pkg = manifest.app.effectiveNamespace
        val main = File(projectDir, "app/src/main")
        val srcRoot = File(main, if (language == SourceLanguage.Java) "java" else "kotlin")
        val pkgDir = File(srcRoot, pkg.replace('.', '/'))
        val generated = GeneratedFiles(projectDir)
        generated.write("app/src/main/AndroidManifest.xml", AndroidSources.manifest(manifest))
        write(File(main, "res/layout/activity_main.xml"), AndroidSources.emptyLayout())
        write(File(main, "res/values/strings.xml"), AndroidSources.strings(manifest.name))
        write(File(main, "res/values/themes.xml"), AndroidSources.themes())
        when (language) {
            SourceLanguage.Java -> write(File(pkgDir, "MainActivity.java"), AndroidSources.javaActivity(pkg))
            SourceLanguage.Kotlin -> write(File(pkgDir, "MainActivity.kt"), AndroidSources.kotlinActivity(pkg))
        }
        write(File(projectDir, "settings.gradle.kts"), GradleFiles.settings(manifest.name))
        write(File(projectDir, "build.gradle.kts"), GradleFiles.root(language))
        generated.write("app/build.gradle.kts", GradleFiles.app(manifest.app))
        write(File(projectDir, ".gitignore"), "build/\n.gradle/\nlocal.properties\n.buildit/backups/\n")
    }

    private fun write(file: File, text: String) {
        file.parentFile.mkdirs()
        file.writeText(text)
    }
}

object AndroidSources {
    fun manifest(m: ProjectManifest): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("""<manifest xmlns:android="http://schemas.android.com/apk/res/android">""")
        appendLine()
        m.app.permissions.distinct().sorted().forEach { appendLine("""    <uses-permission android:name="$it" />""") }
        if (m.app.permissions.isNotEmpty()) appendLine()
        appendLine("""    <application""")
        appendLine("""        android:allowBackup="true"""")
        appendLine("""        android:label="@string/app_name"""")
        appendLine("""        android:theme="@style/AppTheme">""")
        m.modules.first { it.type == ModuleType.Application }.activities.forEach { a ->
            if (a.launcher) {
                appendLine("""        <activity""")
                appendLine("""            android:name=".${a.name}"""")
                appendLine("""            android:exported="true">""")
                appendLine("""            <intent-filter>""")
                appendLine("""                <action android:name="android.intent.action.MAIN" />""")
                appendLine("""                <category android:name="android.intent.category.LAUNCHER" />""")
                appendLine("""            </intent-filter>""")
                appendLine("""        </activity>""")
            } else {
                appendLine("""        <activity android:name=".${a.name}" android:exported="false" />""")
            }
        }
        appendLine("""    </application>""")
        appendLine()
        appendLine("""</manifest>""")
    }

    fun emptyLayout(): String = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
        |    android:layout_width="match_parent"
        |    android:layout_height="match_parent"
        |    android:gravity="center"
        |    android:orientation="vertical"
        |    android:padding="16dp">
        |
        |    <TextView
        |        android:id="@+id/greeting"
        |        android:layout_width="wrap_content"
        |        android:layout_height="wrap_content"
        |        android:text="@string/hello"
        |        android:textSize="20sp" />
        |
        |</LinearLayout>
        |""".trimMargin()

    fun strings(appName: String): String = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<resources>
        |    <string name="app_name">${XmlEscape.text(appName)}</string>
        |    <string name="hello">Hello from Build It</string>
        |</resources>
        |""".trimMargin()

    fun themes(): String = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<resources>
        |    <style name="AppTheme" parent="android:Theme.Material.Light.DarkActionBar" />
        |</resources>
        |""".trimMargin()

    fun javaActivity(pkg: String): String = """
        |package $pkg;
        |
        |import android.app.Activity;
        |import android.os.Bundle;
        |
        |public class MainActivity extends Activity {
        |    @Override
        |    protected void onCreate(Bundle savedInstanceState) {
        |        super.onCreate(savedInstanceState);
        |        setContentView(R.layout.activity_main);
        |    }
        |}
        |""".trimMargin()

    fun kotlinActivity(pkg: String): String = """
        |package $pkg
        |
        |import android.app.Activity
        |import android.os.Bundle
        |
        |class MainActivity : Activity() {
        |    override fun onCreate(savedInstanceState: Bundle?) {
        |        super.onCreate(savedInstanceState)
        |        setContentView(R.layout.activity_main)
        |    }
        |}
        |""".trimMargin()
}

/** Gradle files written alongside the project so it can also be opened in Android Studio. */
object GradleFiles {
    const val AGP_VERSION = "9.5.1"

    fun settings(name: String): String = """
        |pluginManagement {
        |    repositories {
        |        google()
        |        mavenCentral()
        |        gradlePluginPortal()
        |    }
        |}
        |dependencyResolutionManagement {
        |    repositories {
        |        google()
        |        mavenCentral()
        |    }
        |}
        |rootProject.name = "${name.replace("\"", "")}"
        |include(":app")
        |""".trimMargin()

    fun root(language: SourceLanguage): String = """
        |plugins {
        |    id("com.android.application") version "$AGP_VERSION" apply false
        |}
        |""".trimMargin()

    fun app(app: AppConfig): String = """
        |plugins {
        |    id("com.android.application")
        |}
        |
        |android {
        |    namespace = "${app.effectiveNamespace}"
        |    compileSdk = ${app.compileSdk}
        |
        |    defaultConfig {
        |        applicationId = "${app.packageName}"
        |        minSdk = ${app.minSdk}
        |        targetSdk = ${app.targetSdk}
        |        versionCode = ${app.versionCode}
        |        versionName = "${app.versionName}"
        |    }
        |}
        |""".trimMargin() + dependencies(app)

    private fun dependencies(app: AppConfig): String {
        val enabled = app.dependencies.filter { it.enabled }
        if (enabled.isEmpty()) return ""
        return "\ndependencies {\n" + enabled.joinToString("") { "    implementation(\"${it.coordinate}\")\n" } + "}\n"
    }
}

object XmlEscape {
    fun text(s: String): String = buildString {
        s.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("\\'")
                else -> append(c)
            }
        }
    }
}
