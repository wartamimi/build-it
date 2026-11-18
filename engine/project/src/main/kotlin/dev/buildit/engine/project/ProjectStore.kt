package dev.buildit.engine.project

import dev.buildit.core.common.Clock
import dev.buildit.core.common.IdGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException

/** Result of loading a project directory. */
sealed interface LoadResult {
    data class Loaded(val directory: File, val manifest: ProjectManifest, val migratedFrom: Int?) : LoadResult
    data class Failed(val directory: File, val reason: String) : LoadResult
}

class ProjectValidationException(val issues: List<ValidationIssue>) :
    IllegalArgumentException("Invalid project: " + issues.joinToString { "${it.field}:${it.code}" })

/**
 * File-system storage for projects under a workspace root. Every write of the manifest is atomic
 * (temp file + rename) so an interrupted save never leaves a truncated `buildit.json`.
 *
 * Blocking I/O: call from a background dispatcher.
 */
class ProjectStore(
    val workspaceRoot: File,
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.Random,
    private val migrator: ProjectMigrator = ProjectMigrator.Default,
) {
    init {
        workspaceRoot.mkdirs()
    }

    fun create(name: String, packageName: String, template: ProjectTemplate = ProjectTemplates.EmptyActivity): LoadResult.Loaded {
        val now = clock.now()
        val manifest = ProjectManifest(
            id = ids.newId(),
            name = name.trim(),
            createdAt = now,
            updatedAt = now,
            app = AppConfig(packageName = packageName.trim(), language = template.language, namespace = packageName.trim()),
            modules = listOf(template.module),
        )
        val issues = ProjectValidation.validate(manifest)
        if (issues.isNotEmpty()) throw ProjectValidationException(issues)
        val dir = uniqueDirectory(slug(manifest.name))
        val staging = File(workspaceRoot, ".${dir.name}.creating")
        staging.deleteRecursively()
        try {
            staging.mkdirs()
            File(staging, ProjectFormat.INTERNAL_DIR).mkdirs()
            writeManifestAtomically(staging, manifest)
            template.generate(staging, manifest)
            if (!staging.renameTo(dir)) throw IOException("Could not move new project into place: $dir")
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
        return LoadResult.Loaded(dir, manifest, migratedFrom = null)
    }

    fun load(directory: File): LoadResult {
        val file = File(directory, ProjectFormat.MANIFEST_FILE)
        if (!file.isFile) return LoadResult.Failed(directory, "missing_manifest")
        return try {
            val raw = ProjectJson.parseToJsonElement(file.readText()).jsonObject
            val version = migrator.versionOf(raw)
            val migrated = migrator.migrate(raw)
            val manifest = ProjectJson.decodeFromJsonElement(ProjectManifest.serializer(), migrated)
            if (version != ProjectFormat.CURRENT_VERSION) {
                backupFile(directory, file, "manifest-v$version")
                writeManifestAtomically(directory, manifest)
            }
            LoadResult.Loaded(directory, manifest, migratedFrom = version.takeIf { it != ProjectFormat.CURRENT_VERSION })
        } catch (e: UnsupportedFormatException) {
            LoadResult.Failed(directory, e.message ?: "unsupported_format")
        } catch (e: Exception) {
            LoadResult.Failed(directory, "corrupt_manifest: ${e.message}")
        }
    }

    /** Scans the workspace for project directories. Used to rebuild the database index. */
    fun scan(): List<LoadResult> =
        workspaceRoot.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            .orEmpty()
            .sortedBy { it.name }
            .map { load(it) }

    fun save(directory: File, manifest: ProjectManifest): ProjectManifest {
        val issues = ProjectValidation.validate(manifest)
        if (issues.isNotEmpty()) throw ProjectValidationException(issues)
        val updated = manifest.copy(updatedAt = clock.now(), formatVersion = ProjectFormat.CURRENT_VERSION)
        writeManifestAtomically(directory, updated)
        return updated
    }

    fun rename(directory: File, newName: String): ProjectManifest {
        val loaded = load(directory) as? LoadResult.Loaded ?: throw IOException("Cannot load project at $directory")
        return save(directory, loaded.manifest.copy(name = newName.trim()))
    }

    fun duplicate(directory: File, newName: String): LoadResult.Loaded {
        val loaded = load(directory) as? LoadResult.Loaded ?: throw IOException("Cannot load project at $directory")
        val nameIssues = ProjectValidation.validateName(newName)
        if (nameIssues.isNotEmpty()) throw ProjectValidationException(nameIssues)
        val target = uniqueDirectory(slug(newName))
        val staging = File(workspaceRoot, ".${target.name}.copying")
        staging.deleteRecursively()
        try {
            directory.copyRecursively(staging)
            File(staging, ProjectFormat.BACKUP_DIR).deleteRecursively()
            val now = clock.now()
            val manifest = loaded.manifest.copy(id = ids.newId(), name = newName.trim(), createdAt = now, updatedAt = now)
            writeManifestAtomically(staging, manifest)
            if (!staging.renameTo(target)) throw IOException("Could not move duplicate into place: $target")
            return LoadResult.Loaded(target, manifest, migratedFrom = null)
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    /**
     * Deletes a project. The directory is first renamed so a crash mid-delete never leaves a
     * half-deleted project that still looks valid.
     */
    fun delete(directory: File): Boolean {
        require(directory.canonicalFile.parentFile == workspaceRoot.canonicalFile) {
            "Refusing to delete a directory outside the workspace: $directory"
        }
        if (!directory.exists()) return false
        val trash = File(workspaceRoot, ".${directory.name}.deleting.${clock.now()}")
        if (!directory.renameTo(trash)) return directory.deleteRecursively()
        return trash.deleteRecursively()
    }

    /** Removes leftovers of interrupted create/copy/delete operations. */
    fun cleanupInterruptedOperations(): Int {
        val leftovers = workspaceRoot.listFiles { f ->
            f.isDirectory && f.name.startsWith(".") &&
                (f.name.endsWith(".creating") || f.name.endsWith(".copying") || f.name.contains(".deleting.") ||
                    f.name.endsWith(".importing"))
        }.orEmpty()
        leftovers.forEach { it.deleteRecursively() }
        return leftovers.size
    }

    internal fun uniqueDirectory(base: String): File {
        var candidate = File(workspaceRoot, base)
        var i = 2
        while (candidate.exists()) {
            candidate = File(workspaceRoot, "$base-$i")
            i++
        }
        return candidate
    }

    private fun backupFile(directory: File, file: File, label: String) {
        val backups = File(directory, ProjectFormat.BACKUP_DIR).apply { mkdirs() }
        file.copyTo(File(backups, "$label-${clock.now()}.json"), overwrite = true)
    }

    companion object {
        fun slug(name: String): String {
            val s = name.trim().lowercase()
                .map { if (it.isLetterOrDigit()) it else '-' }
                .joinToString("")
                .replace(Regex("-+"), "-")
                .trim('-')
            return s.ifEmpty { "project" }.take(40)
        }

        fun writeManifestAtomically(directory: File, manifest: ProjectManifest) {
            directory.mkdirs()
            val target = File(directory, ProjectFormat.MANIFEST_FILE)
            AtomicFiles.writeText(target, ProjectJson.encodeToString(ProjectManifest.serializer(), manifest))
        }
    }
}

val ProjectJson: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun JsonObject.copyWith(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
    JsonObject(this + pairs)
