package dev.buildit.core.data

import dev.buildit.core.common.Clock
import dev.buildit.core.common.DispatcherProvider
import dev.buildit.core.database.ProjectDao
import dev.buildit.core.database.ProjectEntity
import dev.buildit.core.model.ProjectSummary
import dev.buildit.engine.project.LoadResult
import dev.buildit.engine.project.ManifestSync
import dev.buildit.engine.project.ProjectManifest
import dev.buildit.engine.project.ProjectStore
import dev.buildit.engine.project.ProjectTemplate
import dev.buildit.engine.project.ProjectTemplates
import dev.buildit.engine.project.ProjectTransfer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class SaveResult(val manifest: ProjectManifest, val conflicts: List<String>)

/** A project opened for editing. */
data class OpenProject(val summary: ProjectSummary, val manifest: ProjectManifest, val directory: File)

interface ProjectRepository {
    val projects: Flow<List<ProjectSummary>>
    fun recentProjects(limit: Int = 5): Flow<List<ProjectSummary>>
    val projectCount: Flow<Int>

    suspend fun create(name: String, packageName: String, template: ProjectTemplate = ProjectTemplates.EmptyActivity): ProjectSummary
    suspend fun open(id: String): OpenProject
    suspend fun updateManifest(id: String, transform: (ProjectManifest) -> ProjectManifest): ProjectManifest

    /**
     * Saves the manifest and regenerates the files derived from it (AndroidManifest.xml,
     * app/build.gradle.kts). Files the user edited by hand are not overwritten unless [force];
     * they are returned in [SaveResult.conflicts].
     */
    suspend fun saveSettings(id: String, manifest: ProjectManifest, force: Boolean = false): SaveResult
    suspend fun rename(id: String, newName: String)
    suspend fun duplicate(id: String, newName: String): ProjectSummary
    suspend fun delete(id: String)
    suspend fun import(input: InputStream): ProjectSummary
    suspend fun export(id: String, output: OutputStream)
    suspend fun backup(id: String): File
    suspend fun listBackups(id: String): List<File>
    suspend fun restore(id: String, backup: File)

    /** Rebuilds the index from disk; returns the number of directories that could not be loaded. */
    suspend fun reindex(): Int
}

class DefaultProjectRepository(
    private val store: ProjectStore,
    private val dao: ProjectDao,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock = Clock.System,
) : ProjectRepository {
    private val transfer = ProjectTransfer(store, clock)
    /** Serializes file-system mutations so two operations never race on the same directory. */
    private val mutex = Mutex()

    override val projects: Flow<List<ProjectSummary>> = dao.observeAll().map { list -> list.map { it.toSummary() } }
    override fun recentProjects(limit: Int): Flow<List<ProjectSummary>> = dao.observeRecent(limit).map { l -> l.map { it.toSummary() } }
    override val projectCount: Flow<Int> = dao.observeCount()

    override suspend fun create(name: String, packageName: String, template: ProjectTemplate): ProjectSummary = io {
        val created = store.create(name, packageName, template)
        index(created.directory, created.manifest, lastOpened = null)
    }

    override suspend fun open(id: String): OpenProject = io {
        val entity = dao.get(id) ?: throw IOException("Project $id is not in the index")
        val dir = File(entity.directory)
        when (val r = store.load(dir)) {
            is LoadResult.Failed -> throw IOException("Cannot open project: ${r.reason}")
            is LoadResult.Loaded -> {
                val now = clock.now()
                val summary = index(dir, r.manifest, lastOpened = now)
                OpenProject(summary, r.manifest, dir)
            }
        }
    }

    override suspend fun updateManifest(id: String, transform: (ProjectManifest) -> ProjectManifest): ProjectManifest = io {
        val entity = dao.get(id) ?: throw IOException("Unknown project $id")
        val dir = File(entity.directory)
        val loaded = store.load(dir) as? LoadResult.Loaded ?: throw IOException("Cannot load project")
        val saved = store.save(dir, transform(loaded.manifest))
        index(dir, saved, entity.lastOpenedAt)
        saved
    }

    override suspend fun saveSettings(id: String, manifest: ProjectManifest, force: Boolean): SaveResult = io {
        val entity = dao.get(id) ?: throw IOException("Unknown project $id")
        require(manifest.id == id) { "Manifest belongs to another project" }
        val dir = File(entity.directory)
        val saved = store.save(dir, manifest)
        val sync = ManifestSync.apply(dir, saved, force)
        index(dir, saved, entity.lastOpenedAt)
        SaveResult(saved, sync.conflicts)
    }

    override suspend fun rename(id: String, newName: String) {
        updateManifest(id) { it.copy(name = newName.trim()) }
    }

    override suspend fun duplicate(id: String, newName: String): ProjectSummary = io {
        val entity = dao.get(id) ?: throw IOException("Unknown project $id")
        val copy = store.duplicate(File(entity.directory), newName)
        index(copy.directory, copy.manifest, lastOpened = null)
    }

    override suspend fun delete(id: String) = io {
        val entity = dao.get(id) ?: return@io
        store.delete(File(entity.directory))
        dao.delete(id)
    }

    override suspend fun import(input: InputStream): ProjectSummary = io {
        val imported = transfer.import(input)
        index(imported.directory, imported.manifest, lastOpened = null)
    }

    override suspend fun export(id: String, output: OutputStream) = io {
        val entity = dao.get(id) ?: throw IOException("Unknown project $id")
        transfer.export(File(entity.directory), output)
    }

    override suspend fun backup(id: String): File = io {
        val entity = dao.get(id) ?: throw IOException("Unknown project $id")
        transfer.backup(File(entity.directory))
    }

    override suspend fun listBackups(id: String): List<File> = withContext(dispatchers.io) {
        val entity = dao.get(id) ?: return@withContext emptyList()
        transfer.listBackups(File(entity.directory))
    }

    override suspend fun restore(id: String, backup: File) = io {
        val entity = dao.get(id) ?: throw IOException("Unknown project $id")
        val dir = File(entity.directory)
        transfer.restore(dir, backup)
        val loaded = store.load(dir) as? LoadResult.Loaded ?: throw IOException("Restored project is invalid")
        index(dir, loaded.manifest, entity.lastOpenedAt)
        Unit
    }

    override suspend fun reindex(): Int = io {
        store.cleanupInterruptedOperations()
        val results = store.scan()
        val loaded = results.filterIsInstance<LoadResult.Loaded>()
        dao.replaceAll(loaded.map { it.toEntity(lastOpened = null) })
        results.size - loaded.size
    }

    private suspend fun index(dir: File, manifest: ProjectManifest, lastOpened: Long?): ProjectSummary {
        val entity = LoadResult.Loaded(dir, manifest, null).toEntity(lastOpened)
        dao.upsert(entity)
        return entity.toSummary()
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(dispatchers.io) { mutex.withLock { block() } }
}

private fun LoadResult.Loaded.toEntity(lastOpened: Long?) = ProjectEntity(
    id = manifest.id,
    name = manifest.name,
    packageName = manifest.app.packageName,
    directory = directory.absolutePath,
    createdAt = manifest.createdAt,
    updatedAt = manifest.updatedAt,
    lastOpenedAt = lastOpened,
)

private fun ProjectEntity.toSummary() = ProjectSummary(id, name, packageName, directory, createdAt, updatedAt, lastOpenedAt)
