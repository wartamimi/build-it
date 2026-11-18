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

    @Test fun screenOptionsGoIntoTheManifest() {
        val p = project()
        val app = p.manifest.modules[0]
        val main = app.activities[0].copy(orientation = ScreenOrientation.Portrait, keyboard = KeyboardMode.Resize, window = WindowStyle.Fullscreen)
        val second = ActivityConfig("Second", "activity_second", window = WindowStyle.NoActionBar)
        val xml = AndroidSources.manifest(p.manifest.copy(modules = listOf(app.copy(activities = listOf(main, second)))))
        assertTrue(xml, xml.contains("android:screenOrientation=\"portrait\""))
        assertTrue(xml, xml.contains("android:windowSoftInputMode=\"adjustResize\""))
        assertTrue(xml, xml.contains("android:theme=\"@style/AppTheme.Fullscreen\""))
        assertTrue(xml, xml.contains("<activity android:name=\".Second\" android:theme=\"@style/AppTheme.NoActionBar\" android:exported=\"false\" />"))
        // A hand-edited themes.xml without the styles: framework themes instead
        val framework = AndroidSources.manifest(p.manifest.copy(modules = listOf(app.copy(activities = listOf(main, second)))), themeStyles = false)
        assertTrue(framework, framework.contains("@android:style/Theme.Material.Light.NoActionBar.Fullscreen"))
        // Defaults add nothing
        assertFalse(AndroidSources.manifest(p.manifest).contains("screenOrientation"))
    }

    @Test fun appCompatSwitchChangesLibrariesThemeAndUntouchedScreens() {
        val p = project()
        val themes = File(p.directory, ManifestSync.THEMES)
        // Projects from before 0.14.0 have an untracked themes.xml: it is adopted, not reported
        themes.writeText(AndroidSources.LEGACY_THEMES)
        GeneratedFiles(p.directory).release(ManifestSync.THEMES)
        val on = p.manifest.copy(app = AppCompat.set(p.manifest.app.copy(dependencies = listOf(LibraryDependency("androidx.appcompat:appcompat:1.6.1", enabled = false))), true))
        assertEquals(listOf("androidx.appcompat:appcompat:1.6.1", AppCompat.MATERIAL), on.app.dependencies.map { it.coordinate })
        assertTrue(on.app.dependencies.all { it.enabled })
        val r = ManifestSync.apply(p.directory, on)
        assertEquals(emptyList<String>(), r.conflicts)
        assertTrue(themes.readText().contains("Theme.MaterialComponents.Light.DarkActionBar"))
        assertTrue(themes.readText().contains("<item name=\"windowNoTitle\">true</item>"))

        val main = File(p.directory, "app/src/main/java/com/example/files/MainActivity.java")
        assertEquals(SourceSync(listOf("MainActivity"), emptyList()), AppCompatSources.sync(p.directory, on))
        assertTrue(main.readText().contains("extends AppCompatActivity"))
        // An edited screen is reported, not rewritten
        main.writeText(main.readText().replace("setContentView", "/* mine */ setContentView"))
        val off = on.copy(app = AppCompat.set(on.app, false))
        assertTrue(off.app.dependencies.none { it.enabled })
        assertEquals(SourceSync(emptyList(), listOf("MainActivity")), AppCompatSources.sync(p.directory, off))
        assertTrue(main.readText().contains("/* mine */"))
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
