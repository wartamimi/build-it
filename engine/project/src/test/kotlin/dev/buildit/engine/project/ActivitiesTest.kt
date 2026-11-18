package dev.buildit.engine.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ActivitiesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun addsActivityLayoutAndManifestEntry() {
        val p = ProjectStore(tmp.root).create("A", "com.example.a")
        val m = Activities.add(p.directory, p.manifest, "DetailActivity", "activity_detail", "<FrameLayout/>")
        assertEquals(listOf("MainActivity", "DetailActivity"), m.modules[0].activities.map { it.name })
        val src = File(p.directory, "app/src/main/java/com/example/a/DetailActivity.java").readText()
        assertTrue(src.contains("class DetailActivity"))
        assertTrue(src.contains("R.layout.activity_detail"))
        assertEquals(listOf("activity_detail", "activity_main"), Activities.layouts(p.directory))
        val r = ManifestSync.apply(p.directory, m)
        assertTrue(r.written.contains("app/src/main/AndroidManifest.xml"))
        assertTrue(File(p.directory, "app/src/main/AndroidManifest.xml").readText().contains(".DetailActivity"))
    }

    @Test fun rejectsBadOrDuplicateNames() {
        val p = ProjectStore(tmp.root).create("A", "com.example.a")
        val codes = Activities.validate(p.manifest, p.directory, "MainActivity", "activity_main").map { it.code }
        assertEquals(listOf("exists", "exists"), codes)
        assertEquals(listOf("invalid_class", "invalid_resource"), Activities.validate(p.manifest, p.directory, "lower", "Bad-Name").map { it.code })
    }
}
