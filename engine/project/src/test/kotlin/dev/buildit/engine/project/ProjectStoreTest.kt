package dev.buildit.engine.project

import dev.buildit.core.common.Clock
import dev.buildit.core.common.IdGenerator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private var time = 1_000L
    private var nextId = 0
    private val clock = Clock { time++ }
    private val ids = IdGenerator { "id-${nextId++}" }
    private fun store() = ProjectStore(tmp.root, clock, ids)

    @Test fun createWritesManifestAndSources() {
        val created = store().create("My App", "com.example.myapp")
        assertEquals("my-app", created.directory.name)
        assertTrue(File(created.directory, "buildit.json").isFile)
        assertTrue(File(created.directory, "app/src/main/java/com/example/myapp/MainActivity.java").isFile)
        assertTrue(File(created.directory, "app/src/main/res/layout/activity_main.xml").isFile)
        val manifestXml = File(created.directory, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifestXml.contains("android.intent.category.LAUNCHER"))
    }

    @Test fun kotlinTemplateWritesKotlinActivity() {
        val created = store().create("K", "com.example.k", ProjectTemplates.EmptyActivityKotlin)
        assertTrue(File(created.directory, "app/src/main/kotlin/com/example/k/MainActivity.kt").isFile)
        assertEquals(SourceLanguage.Kotlin, created.manifest.app.language)
    }

    @Test fun createAppliesTheSettingsChosenBeforehand() {
        val colors = ThemeColors("#FF009688", "#FF00796B", "#FFFFC107")
        val created = store().create("Shop", "com.example.shop", options = NewProjectOptions(versionCode = 3, versionName = "0.3", minSdk = 26, appCompat = true, colors = colors))
        val app = created.manifest.app
        assertEquals(3, app.versionCode); assertEquals("0.3", app.versionName); assertEquals(26, app.minSdk)
        assertTrue(app.appCompat)
        assertTrue(app.dependencies.any { it.module == "androidx.appcompat:appcompat" && it.enabled })
        assertEquals(colors, app.colors)
        val res = File(created.directory, "app/src/main/res/values")
        assertTrue(File(res, "colors.xml").readText().contains("<color name=\"colorPrimary\">#FF009688</color>"))
        assertTrue(File(res, "themes.xml").readText().contains("<item name=\"colorPrimary\">@color/colorPrimary</item>"))
        val main = File(created.directory, "app/src/main/java/com/example/shop/MainActivity.java").readText()
        assertTrue(main, main.contains("AppCompatActivity"))
        // Reloads with the same settings.
        assertEquals(colors, (store().load(created.directory) as LoadResult.Loaded).manifest.app.colors)
    }

    @Test fun createRejectsInvalidPackage() {
        try {
            store().create("App", "com.class.app")
            fail("expected validation failure")
        } catch (e: ProjectValidationException) {
            assertEquals("keyword", e.issues.single().code)
        }
        assertTrue(tmp.root.listFiles()!!.none { !it.name.startsWith(".") })
    }

    @Test fun createUsesUniqueDirectories() {
        val s = store()
        val a = s.create("Same", "com.example.a")
        val b = s.create("Same", "com.example.b")
        assertNotEquals(a.directory, b.directory)
        assertEquals("same-2", b.directory.name)
    }

    @Test fun loadRoundTrips() {
        val s = store()
        val created = s.create("Round", "com.example.round")
        val loaded = s.load(created.directory) as LoadResult.Loaded
        assertEquals(created.manifest, loaded.manifest)
        assertEquals(null, loaded.migratedFrom)
    }

    @Test fun loadReportsCorruptManifest() {
        val dir = tmp.newFolder("broken")
        File(dir, "buildit.json").writeText("{ not json")
        val result = store().load(dir)
        assertTrue(result is LoadResult.Failed)
    }

    @Test fun loadRejectsNewerFormat() {
        val s = store()
        val created = s.create("Future", "com.example.future")
        val file = File(created.directory, "buildit.json")
        file.writeText(file.readText().replace("\"formatVersion\": 1", "\"formatVersion\": 99"))
        val result = s.load(created.directory) as LoadResult.Failed
        assertTrue(result.reason.contains("newer"))
    }

    @Test fun saveUpdatesTimestampAndRenameWorks() {
        val s = store()
        val created = s.create("Old", "com.example.old")
        val renamed = s.rename(created.directory, "New Name")
        assertEquals("New Name", renamed.name)
        assertTrue(renamed.updatedAt > created.manifest.updatedAt)
        assertEquals("New Name", (s.load(created.directory) as LoadResult.Loaded).manifest.name)
    }

    @Test fun duplicateGetsNewIdAndCopiesSources() {
        val s = store()
        val created = s.create("Orig", "com.example.orig")
        val copy = s.duplicate(created.directory, "Copy")
        assertNotEquals(created.manifest.id, copy.manifest.id)
        assertTrue(File(copy.directory, "app/src/main/java/com/example/orig/MainActivity.java").isFile)
        assertEquals(2, s.scan().size)
    }

    @Test fun deleteRemovesProjectAndRefusesOutsideWorkspace() {
        val s = store()
        val created = s.create("Gone", "com.example.gone")
        assertTrue(s.delete(created.directory))
        assertFalse(created.directory.exists())
        try {
            s.delete(tmp.newFolder("elsewhere", "nested"))
            fail("expected refusal")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun cleanupRemovesInterruptedOperations() {
        File(tmp.root, ".x.creating").mkdirs()
        File(tmp.root, ".y.deleting.5").mkdirs()
        assertEquals(2, store().cleanupInterruptedOperations())
    }

    @Test fun exportImportRoundTripCreatesNewId() {
        val s = store()
        val created = s.create("Zip", "com.example.zip")
        val bytes = ByteArrayOutputStream().also { ProjectTransfer(s, clock).export(created.directory, it) }.toByteArray()
        val imported = ProjectTransfer(s, clock).import(ByteArrayInputStream(bytes), ids)
        assertNotEquals(created.manifest.id, imported.manifest.id)
        assertEquals("Zip", imported.manifest.name)
        assertTrue(File(imported.directory, "app/src/main/AndroidManifest.xml").isFile)
    }

    @Test fun importRejectsZipSlip() {
        val bytes = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                z.putNextEntry(ZipEntry("../evil.txt")); z.write(1); z.closeEntry()
            }
        }.toByteArray()
        try {
            ProjectTransfer(store(), clock).import(ByteArrayInputStream(bytes))
            fail("expected rejection")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Unsafe"))
        }
        assertFalse(File(tmp.root.parentFile, "evil.txt").exists())
    }

    @Test fun importRejectsNonProjectZip() {
        val bytes = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry("readme.txt")); z.write(1); z.closeEntry() }
        }.toByteArray()
        try {
            ProjectTransfer(store(), clock).import(ByteArrayInputStream(bytes))
            fail("expected rejection")
        } catch (_: IOException) {
        }
        assertTrue(tmp.root.listFiles()!!.isEmpty())
    }

    @Test fun backupAndRestore() {
        val s = store()
        val t = ProjectTransfer(s, clock)
        val created = s.create("Back", "com.example.back")
        val layout = File(created.directory, "app/src/main/res/layout/activity_main.xml")
        val original = layout.readText()
        val backup = t.backup(created.directory)
        layout.writeText("changed")
        t.restore(created.directory, backup)
        assertEquals(original, layout.readText())
        assertEquals(2, t.listBackups(created.directory).size) // original + safety backup
    }

    @Test fun migratorAppliesStepsInOrder() {
        val m = ProjectMigrator(
            listOf(
                object : FormatMigration {
                    override val fromVersion = 1
                    override fun migrate(document: JsonObject) =
                        JsonObject(document + ("formatVersion" to JsonPrimitive(2)) + ("a" to JsonPrimitive(true)))
                },
                object : FormatMigration {
                    override val fromVersion = 2
                    override fun migrate(document: JsonObject) =
                        JsonObject(document + ("formatVersion" to JsonPrimitive(3)) + ("b" to JsonPrimitive(true)))
                },
            ),
            currentVersion = 3,
        )
        val out = m.migrate(JsonObject(mapOf("formatVersion" to JsonPrimitive(1))))
        assertEquals("3", out["formatVersion"].toString())
        assertTrue(out.containsKey("a") && out.containsKey("b"))
    }

    @Test fun migrationOnLoadBacksUpOriginal() {
        val created = store().create("Mig", "com.example.mig")
        val file = File(created.directory, "buildit.json")
        val v0 = ProjectJson.parseToJsonElement(file.readText()).jsonObject.let {
            JsonObject(it + ("formatVersion" to JsonPrimitive(0)))
        }
        file.writeText(v0.toString())
        val migrating = ProjectStore(
            tmp.root, clock, ids,
            ProjectMigrator(listOf(object : FormatMigration {
                override val fromVersion = 0
                override fun migrate(document: JsonObject) = JsonObject(document + ("formatVersion" to JsonPrimitive(1)))
            })),
        )
        val loaded = migrating.load(created.directory) as LoadResult.Loaded
        assertEquals(0, loaded.migratedFrom)
        assertTrue(file.readText().contains("\"formatVersion\": 1"))
        assertTrue(File(created.directory, ProjectFormat.BACKUP_DIR).listFiles()!!.any { it.name.startsWith("manifest-v0") })
    }
}
