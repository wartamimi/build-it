package dev.buildit.engine.project

/**
 * The AppCompat switch of the Library Manager (like Sketchware's "AppCompat and Design"). On:
 * the app depends on AndroidX AppCompat and Material Components, screens extend
 * AppCompatActivity and the theme comes from Material Components. Off: the libraries stay listed
 * but are turned off, and new code uses the framework Activity.
 */
object AppCompat {
    const val APPCOMPAT = "androidx.appcompat:appcompat:1.7.1"
    const val MATERIAL = "com.google.android.material:material:1.12.0"
    private val required = listOf(APPCOMPAT, MATERIAL)

    /** Modules the switch manages, `group:artifact`. */
    val modules: Set<String> = required.map { it.substringBeforeLast(':') }.toSet()

    /**
     * [app] with the switch set. Turning it on adds the libraries (or turns on the ones already
     * listed, keeping their version); turning it off turns them off without removing them.
     */
    fun set(app: AppConfig, on: Boolean): AppConfig {
        val deps = app.dependencies.map { d -> if (d.module in modules) d.copy(enabled = on) else d }
        val missing = if (on) required.filter { c -> deps.none { it.module == c.substringBeforeLast(':') } }.map { LibraryDependency(it) } else emptyList()
        return app.copy(appCompat = on, dependencies = deps + missing)
    }
}

/** What [AppCompatSources.sync] did: screens updated, and screens whose code the user has to change. */
data class SourceSync(val updated: List<String>, val needsChange: List<String>)

object AppCompatSources {
    private val plainBase = Regex("""\bextends\s+Activity\b|:\s*Activity\(\)""")
    private val compatBase = Regex("""\bAppCompatActivity\b""")

    /**
     * Brings the screens' base class in line with the switch. A screen still exactly as Build It
     * created it is rewritten; any other screen is only reported (its code is the user's). Screens
     * generated from blocks are reported too and change on their next save.
     */
    fun sync(projectDir: java.io.File, manifest: ProjectManifest): SourceSync {
        val ac = manifest.app.appCompat
        val ns = manifest.app.effectiveNamespace
        val kotlin = manifest.app.language == SourceLanguage.Kotlin
        val dir = java.io.File(projectDir, "app/src/main/${if (kotlin) "kotlin" else "java"}/${ns.replace('.', '/')}")
        val updated = mutableListOf<String>()
        val needs = mutableListOf<String>()
        manifest.modules.filter { it.type == ModuleType.Application }.flatMap { it.activities }.forEach { a ->
            val file = java.io.File(dir, "${a.name}.${if (kotlin) "kt" else "java"}")
            if (!file.isFile) return@forEach
            val text = file.readText()
            fun template(compat: Boolean) = (if (kotlin) AndroidSources.kotlinActivity(ns, compat) else AndroidSources.javaActivity(ns, compat))
                .replace("MainActivity", a.name).replace("activity_main", a.layout)
            when {
                text == template(!ac) -> { AtomicFiles.writeText(file, template(ac)); updated += a.name }
                ac && plainBase.containsMatchIn(text) && !compatBase.containsMatchIn(text) -> needs += a.name
                !ac && compatBase.containsMatchIn(text) -> needs += a.name
            }
        }
        return SourceSync(updated, needs)
    }
}
