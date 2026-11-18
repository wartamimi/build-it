package dev.buildit.engine.project

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ZIP export/import of a project directory. The archive contains the project directory's contents
 * at its root (buildit.json at top level). Build outputs and backups are excluded.
 */
object ProjectArchive {
    private val EXCLUDED_DIRS = setOf("build", ".gradle", ProjectFormat.BACKUP_DIR)
    const val MAX_ENTRIES = 20_000
    const val MAX_UNCOMPRESSED_BYTES = 512L * 1024 * 1024

    fun export(projectDir: File, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            projectDir.walkTopDown()
                .onEnter { dir -> dir == projectDir || dir.relativeTo(projectDir).invariantSeparatorsPath !in EXCLUDED_DIRS && dir.name !in EXCLUDED_DIRS }
                .filter { it.isFile }
                .sortedBy { it.relativeTo(projectDir).invariantSeparatorsPath }
                .forEach { file ->
                    val entry = ZipEntry(file.relativeTo(projectDir).invariantSeparatorsPath)
                    entry.time = file.lastModified()
                    zip.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    /**
     * Extracts [input] into [targetDir], which must not exist yet. Rejects path traversal ("zip
     * slip"), oversized archives, and archives without a manifest.
     */
    fun extract(input: InputStream, targetDir: File) {
        if (targetDir.exists()) throw IOException("Target already exists: $targetDir")
        val root = targetDir.canonicalFile
        var entries = 0
        var total = 0L
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) throw IOException("Archive has too many entries")
                    val dest = File(root, entry.name).canonicalFile
                    if (!dest.path.startsWith(root.path + File.separator)) {
                        throw IOException("Unsafe entry path: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        dest.mkdirs()
                    } else {
                        dest.parentFile.mkdirs()
                        dest.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_UNCOMPRESSED_BYTES) throw IOException("Archive is too large")
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            if (!File(root, ProjectFormat.MANIFEST_FILE).isFile) {
                throw IOException("Archive is not a Build It project (no ${ProjectFormat.MANIFEST_FILE})")
            }
        } catch (t: Throwable) {
            root.deleteRecursively()
            throw t
        }
    }
}

/** Import/export/backup operations built on [ProjectStore] and [ProjectArchive]. */
class ProjectTransfer(private val store: ProjectStore, private val clock: dev.buildit.core.common.Clock = dev.buildit.core.common.Clock.System) {

    /** Imports an archive as a new project with a fresh id, so importing twice never collides. */
    fun import(input: InputStream, ids: dev.buildit.core.common.IdGenerator = dev.buildit.core.common.IdGenerator.Random): LoadResult.Loaded {
        val staging = File(store.workspaceRoot, ".import-${clock.now()}.importing")
        staging.deleteRecursively()
        ProjectArchive.extract(input, staging)
        try {
            val loaded = store.load(staging)
            if (loaded is LoadResult.Failed) throw IOException("Imported project is invalid: ${loaded.reason}")
            loaded as LoadResult.Loaded
            val manifest = loaded.manifest.copy(id = ids.newId())
            ProjectStore.writeManifestAtomically(staging, manifest)
            val target = store.uniqueDirectory(ProjectStore.slug(manifest.name))
            if (!staging.renameTo(target)) throw IOException("Could not move imported project into place")
            return LoadResult.Loaded(target, manifest, loaded.migratedFrom)
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    fun export(projectDir: File, out: OutputStream) = ProjectArchive.export(projectDir, out)

    /** Writes a timestamped ZIP backup into the project's backup folder and returns it. */
    fun backup(projectDir: File, keep: Int = 5): File {
        val backups = File(projectDir, ProjectFormat.BACKUP_DIR).apply { mkdirs() }
        val file = File(backups, "backup-${clock.now()}.zip")
        val tmp = File(backups, ".${file.name}.tmp")
        tmp.outputStream().use { ProjectArchive.export(projectDir, it) }
        if (!tmp.renameTo(file)) throw IOException("Could not finalize backup")
        listBackups(projectDir).drop(keep).forEach { it.delete() }
        return file
    }

    fun listBackups(projectDir: File): List<File> =
        File(projectDir, ProjectFormat.BACKUP_DIR).listFiles { f -> f.isFile && f.name.startsWith("backup-") && f.name.endsWith(".zip") }
            .orEmpty().sortedByDescending { it.name.removePrefix("backup-").removeSuffix(".zip").toLongOrNull() ?: 0 }

    /**
     * Restores [backup] into [projectDir]. The current state is first saved as a new backup, so a
     * restore can itself be undone.
     */
    fun restore(projectDir: File, backup: File) {
        backup(projectDir, keep = Int.MAX_VALUE)
        val staging = File(store.workspaceRoot, ".restore-${clock.now()}.importing")
        backup.inputStream().use { ProjectArchive.extract(it, staging) }
        val backupsDir = File(projectDir, ProjectFormat.BACKUP_DIR)
        val keptBackups = File(store.workspaceRoot, ".restore-backups-${clock.now()}.importing")
        backupsDir.renameTo(keptBackups)
        projectDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        staging.listFiles().orEmpty().forEach { it.renameTo(File(projectDir, it.name)) }
        staging.deleteRecursively()
        File(projectDir, ProjectFormat.INTERNAL_DIR).mkdirs()
        if (!keptBackups.renameTo(backupsDir)) keptBackups.copyRecursively(backupsDir, overwrite = true)
        keptBackups.deleteRecursively()
    }
}
