package dev.buildit.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProjectDaoTest {
    private lateinit var db: BuildItDatabase
    private lateinit var dao: ProjectDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BuildItDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.projectDao()
    }

    @After fun tearDown() = db.close()

    private fun entity(id: String, updated: Long, opened: Long? = null) =
        ProjectEntity(id, "P$id", "com.example.p$id", "/w/$id", 1, updated, opened)

    @Test fun recentOrdersByLastOpenedAndSkipsNeverOpened() = runTest {
        dao.upsert(entity("a", 10, opened = 100))
        dao.upsert(entity("b", 20, opened = 300))
        dao.upsert(entity("c", 30))
        assertEquals(listOf("b", "a"), dao.observeRecent(10).first().map { it.id })
        assertEquals(listOf("c", "b", "a"), dao.observeAll().first().map { it.id })
        assertEquals(3, dao.observeCount().first())
    }

    @Test fun markOpenedAndDelete() = runTest {
        dao.upsert(entity("a", 10))
        dao.markOpened("a", 55)
        assertEquals(55L, dao.get("a")!!.lastOpenedAt)
        dao.delete("a")
        assertEquals(null, dao.get("a"))
    }

    @Test fun replaceAllKeepsOpenedTimes() = runTest {
        dao.upsert(entity("a", 10, opened = 99))
        dao.upsert(entity("gone", 10))
        dao.replaceAll(listOf(entity("a", 11), entity("new", 12)))
        assertEquals(setOf("a", "new"), dao.all().map { it.id }.toSet())
        assertEquals(99L, dao.get("a")!!.lastOpenedAt)
    }
}
