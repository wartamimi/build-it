package dev.buildit.engine.project

import java.io.File
import java.io.IOException

/** Adds screens (activity + layout) to a project. */
object Activities {
    private val CLASS_NAME = Regex("[A-Z][A-Za-z0-9_]*")
    val RESOURCE_NAME = Regex("[a-z][a-z0-9_]*")

    fun validate(manifest: ProjectManifest, projectDir: File, name: String, layout: String): List<ValidationIssue> = buildList {
        if (!CLASS_NAME.matches(name)) add(ValidationIssue("activityName", "invalid_class"))
        val app = manifest.modules.first { it.type == ModuleType.Application }
        if (app.activities.any { it.name == name }) add(ValidationIssue("activityName", "exists"))
        if (!RESOURCE_NAME.matches(layout)) add(ValidationIssue("layoutName", "invalid_resource"))
        if (File(projectDir, "app/src/main/res/layout/$layout.xml").exists()) add(ValidationIssue("layoutName", "exists"))
    }

    /**
     * Writes the activity source and its layout, and returns the updated manifest (not yet saved).
     * Nothing existing is overwritten.
     */
    fun add(projectDir: File, manifest: ProjectManifest, name: String, layout: String, layoutXml: String): ProjectManifest {
        val issues = validate(manifest, projectDir, name, layout)
        if (issues.isNotEmpty()) throw ProjectValidationException(issues)
        val ns = manifest.app.effectiveNamespace
        val kotlin = manifest.app.language == SourceLanguage.Kotlin
        val srcDir = File(projectDir, "app/src/main/${if (kotlin) "kotlin" else "java"}/${ns.replace('.', '/')}")
        val src = File(srcDir, "$name.${if (kotlin) "kt" else "java"}")
        if (src.exists()) throw IOException("${src.name} already exists")
        val layoutFile = File(projectDir, "app/src/main/res/layout/$layout.xml")
        srcDir.mkdirs(); layoutFile.parentFile.mkdirs()
        val code = if (kotlin) AndroidSources.kotlinActivity(ns).replace("MainActivity", name).replace("activity_main", layout)
        else AndroidSources.javaActivity(ns).replace("MainActivity", name).replace("activity_main", layout)
        AtomicFiles.writeText(layoutFile, layoutXml)
        AtomicFiles.writeText(src, code)
        val modules = manifest.modules.map { m ->
            if (m.type == ModuleType.Application) m.copy(activities = m.activities + ActivityConfig(name, layout)) else m
        }
        return manifest.copy(modules = modules)
    }

    /** Layout resource names (without .xml) in the app module. */
    fun layouts(projectDir: File): List<String> =
        File(projectDir, "app/src/main/res/layout").listFiles { f -> f.isFile && f.name.endsWith(".xml") }
            .orEmpty().map { it.name.removeSuffix(".xml") }.sorted()
}
