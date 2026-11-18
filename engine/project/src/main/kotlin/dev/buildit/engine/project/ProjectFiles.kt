package dev.buildit.engine.project

import java.io.File
import java.io.IOException

/** A file or folder inside a project, addressed by its project-relative path. */
data class ProjectEntry(val path: String, val name: String, val isDirectory: Boolean, val size: Long, val lastModified: Long)

/**
 * File operations confined to one project directory. Every path is checked to stay inside the
 * project, and Build It's own metadata (`buildit.json`, `.buildit/`) cannot be renamed or deleted
 * through this API.
 */
class ProjectFiles(private val root: File) {
    private val rootPath = root.canonicalPath

    fun list(path: String = ""): List<ProjectEntry> {
        val dir = resolve(path)
        if (!dir.isDirectory) throw IOException("Not a folder: $path")
        return dir.listFiles().orEmpty()
            .map { it.toEntry() }
            .sortedWith(compareBy<ProjectEntry>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun readText(path: String, maxBytes: Long = MAX_TEXT_BYTES): String {
        val f = resolve(path)
        if (!f.isFile) throw IOException("Not a file: $path")
        if (f.length() > maxBytes) throw IOException("File is too large to open as text (${f.length()} bytes)")
        return f.readText()
    }

    fun writeText(path: String, text: String) {
        val f = resolve(path)
        checkMutable(path)
        AtomicFiles.writeText(f, text)
    }

    fun createFile(parent: String, name: String): ProjectEntry {
        validateName(name)
        val f = File(resolve(parent), name)
        checkInside(f)
        if (f.exists()) throw IOException("“$name” already exists")
        f.parentFile.mkdirs()
        if (!f.createNewFile()) throw IOException("Could not create $name")
        return f.toEntry()
    }

    fun createFolder(parent: String, name: String): ProjectEntry {
        validateName(name)
        val f = File(resolve(parent), name)
        checkInside(f)
        if (f.exists()) throw IOException("“$name” already exists")
        if (!f.mkdirs()) throw IOException("Could not create folder $name")
        return f.toEntry()
    }

    fun rename(path: String, newName: String): ProjectEntry {
        checkMutable(path)
        validateName(newName)
        val f = resolve(path)
        val target = File(f.parentFile, newName)
        checkInside(target)
        if (target.exists()) throw IOException("“$newName” already exists")
        if (!f.renameTo(target)) throw IOException("Could not rename ${f.name}")
        return target.toEntry()
    }

    fun delete(path: String) {
        checkMutable(path)
        val f = resolve(path)
        if (!f.exists()) return
        if (!f.deleteRecursively()) throw IOException("Could not delete ${f.name}")
    }

    fun isProtected(path: String): Boolean {
        val norm = path.trim('/')
        return norm.isEmpty() || norm == ProjectFormat.MANIFEST_FILE || norm == ProjectFormat.INTERNAL_DIR ||
            norm.startsWith(ProjectFormat.INTERNAL_DIR + "/")
    }

    private fun checkMutable(path: String) {
        if (isProtected(path)) throw IOException("“$path” is managed by Build It and can’t be changed here")
    }

    private fun validateName(name: String) {
        if (name.isBlank() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == '\u0000' }) {
            throw IOException("Invalid name: “$name”")
        }
    }

    fun resolve(path: String): File {
        val f = if (path.isEmpty()) root.canonicalFile else File(root, path).canonicalFile
        checkInside(f)
        return f
    }

    private fun checkInside(f: File) {
        val p = f.canonicalPath
        if (p != rootPath && !p.startsWith(rootPath + File.separator)) throw IOException("Path is outside the project")
    }

    private fun File.toEntry() = ProjectEntry(
        path = canonicalFile.relativeTo(File(rootPath)).invariantSeparatorsPath,
        name = name, isDirectory = isDirectory, size = if (isFile) length() else 0, lastModified = lastModified(),
    )

    companion object {
        const val MAX_TEXT_BYTES = 2L * 1024 * 1024
    }
}

/** Applies project-level settings to the files Build It generates from the manifest. */
object ManifestSync {
    data class Result(val written: List<String>, val conflicts: List<String>)

    const val THEMES = "app/src/main/res/values/themes.xml"

    fun apply(projectDir: File, manifest: ProjectManifest, force: Boolean = false): Result {
        val g = GeneratedFiles(projectDir)
        val written = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        // themes.xml first: the manifest refers to its window styles only when they are there.
        val themesFile = File(projectDir, THEMES)
        val legacy = themesFile.isFile && !g.isTracked(THEMES) && themesFile.readText() == AndroidSources.LEGACY_THEMES
        when (val r = g.write(THEMES, AndroidSources.themes(manifest.app.appCompat), force || legacy)) {
            GeneratedFiles.WriteResult.Written -> written += THEMES
            GeneratedFiles.WriteResult.Unchanged -> Unit
            is GeneratedFiles.WriteResult.UserModified -> conflicts += r.path
        }
        val styles = AndroidSources.hasWindowStyles(themesFile.readText())
        val outputs = mapOf(
            "app/src/main/AndroidManifest.xml" to AndroidSources.manifest(manifest, styles),
            "app/build.gradle.kts" to GradleFiles.app(manifest.app),
        )
        outputs.forEach { (path, content) ->
            when (val r = g.write(path, content, force)) {
                GeneratedFiles.WriteResult.Written -> written += path
                GeneratedFiles.WriteResult.Unchanged -> Unit
                is GeneratedFiles.WriteResult.UserModified -> conflicts += r.path
            }
        }
        return Result(written, conflicts)
    }
}
