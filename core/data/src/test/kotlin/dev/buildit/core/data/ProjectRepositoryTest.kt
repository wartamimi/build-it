package dev.buildit.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.buildit.core.common.DispatcherProvider
import dev.buildit.core.database.BuildItDatabase
import dev.buildit.engine.project.ProjectStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ProjectRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: BuildItDatabase
    private lateinit var repo: DefaultProjectRepository
    private lateinit var store: ProjectStore

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BuildItDatabase::class.java)
            .allowMainThreadQueries().build()
        store = ProjectStore(tmp.root)
        repo = DefaultProjectRepository(store, db.projectDao(), object : DispatcherProvider {
            val d: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
            override val io = d
            override val default = d
            override val main = d
        })
    }

    @After fun tearDown() = db.close()

    @Test fun createOpenRenameDelete() = runTest {
        val p = repo.create("Demo", "com.example.demo")
        assertEquals(listOf("Demo"), repo.projects.first().map { it.name })
        assertTrue(repo.recentProjects().first().isEmpty())

        val opened = repo.open(p.id)
        assertEquals("com.example.demo", opened.manifest.app.packageName)
        assertEquals(listOf(p.id), repo.recentProjects().first().map { it.id })

        repo.rename(p.id, "Renamed")
        assertEquals("Renamed", repo.projects.first().single().name)

        repo.delete(p.id)
        assertTrue(repo.projects.first().isEmpty())
        assertFalse(File(p.directory).exists())
    }

    @Test fun duplicateExportImport() = runTest {
        val p = repo.create("Src", "com.example.src")
        repo.duplicate(p.id, "Copy")
        val bytes = ByteArrayOutputStream().also { repo.export(p.id, it) }.toByteArray()
        repo.import(ByteArrayInputStream(bytes))
        assertEquals(3, repo.projectCount.first())
    }

    @Test fun reindexRecoversFromDisk() = runTest {
        repo.create("A", "com.example.a")
        store.create("Outside", "com.example.outside") // written without the index
        File(tmp.root, "garbage").mkdirs()
        val failed = repo.reindex()
        assertEquals(1, failed)
        assertEquals(setOf("A", "Outside"), repo.projects.first().map { it.name }.toSet())
    }

    @Test fun updateManifestPersists() = runTest {
        val p = repo.create("V", "com.example.v")
        repo.updateManifest(p.id) { it.copy(app = it.app.copy(versionCode = 7, versionName = "2.0")) }
        assertEquals(7, repo.open(p.id).manifest.app.versionCode)
        val backup = repo.backup(p.id)
        assertNotNull(repo.listBackups(p.id).firstOrNull { it == backup })
    }
}
