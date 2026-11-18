package dev.buildit.engine.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ProjectFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun project(): LoadResult.Loaded = ProjectStore(tmp.root).create("Files", "com.example.files")

    @Test fun listSortsFoldersFirst() {
        val p = project()
        val names = ProjectFiles(p.directory).list().map { it.name }
        assertTrue(names.indexOf("app") < names.indexOf("buildit.json"))
    }

    @Test fun createRenameDeleteInsideProject() {
        val files = ProjectFiles(project().directory)
        files.createFolder("app/src/main", "assets")
        files.createFile("app/src/main/assets", "data.txt")
        files.writeText("app/src/main/assets/data.txt", "hi")
        assertEquals("hi", files.readText("app/src/main/assets/data.txt"))
        files.rename("app/src/main/assets/data.txt", "info.txt")
        assertEquals(listOf("info.txt"), files.list("app/src/main/assets").map { it.name })
        files.delete("app/src/main/assets")
        assertFalse(files.resolve("app/src/main/assets").exists())
    }

    @Test fun refusesEscapesAndProtectedPaths() {
        val files = ProjectFiles(project().directory)
        listOf<() -> Unit>(
            { files.readText("../outside.txt") },
            { files.createFile("", "../x") },
            { files.delete("buildit.json") },
            { files.rename(".buildit", "x") },
            { files.writeText(".buildit/generated.json", "{}") },
        ).forEach {
            try { it(); fail("expected refusal") } catch (_: IOException) { } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun generatedFilesDetectUserEdits() {
        val p = project()
        val g = GeneratedFiles(p.directory)
        assertFalse(g.isUserModified("app/build.gradle.kts"))
        val gradle = File(p.directory, "app/build.gradle.kts")
        gradle.appendText("\n// my change\n")
        assertTrue(g.isUserModified("app/build.gradle.kts"))
        val r = g.write("app/build.gradle.kts", "new content")
        assertTrue(r is GeneratedFiles.WriteResult.UserModified)
        assertTrue(gradle.readText().contains("my change"))
        assertEquals(GeneratedFiles.WriteResult.Written, g.write("app/build.gradle.kts", "new content", force = true))
        assertEquals(GeneratedFiles.WriteResult.Unchanged, g.write("app/build.gradle.kts", "new content"))
    }

    @Test fun manifestSyncUpdatesVersionAndReportsConflicts() {
        val p = project()
        val updated = p.manifest.copy(app = p.manifest.app.copy(versionCode = 5, versionName = "1.5", packageName = "com.example.renamed"))
        val r = ManifestSync.apply(p.directory, updated)
        assertEquals(listOf("app/build.gradle.kts"), r.written)
        val gradle = File(p.directory, "app/build.gradle.kts").readText()
        assertTrue(gradle.contains("versionCode = 5"))
        assertTrue(gradle.contains("applicationId = \"com.example.renamed\""))
        assertTrue("namespace stays with the sources", gradle.contains("namespace = \"com.example.files\""))

        File(p.directory, "app/src/main/AndroidManifest.xml").appendText("<!-- edited -->")
        val withActivity = updated.copy(modules = listOf(updated.modules[0].copy(activities = updated.modules[0].activities + ActivityConfig("Second", "activity_second"))))
        val r2 = ManifestSync.apply(p.directory, withActivity)
        assertEquals(listOf("app/src/main/AndroidManifest.xml"), r2.conflicts)
    }
}
