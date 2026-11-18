package dev.buildit.engine.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class CollectionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun savesListsRenamesAndDeletes() {
        val c = CollectionStore(tmp.newFolder("collection"))
        c.saveText(CollectionKind.Widgets, "Login form", "<LinearLayout/>")
        try { c.saveText(CollectionKind.Widgets, "Login form", "x"); fail() } catch (_: IOException) {}
        c.saveText(CollectionKind.Widgets, "Login form", "<FrameLayout/>", replace = true)
        val item = c.list(CollectionKind.Widgets).single()
        assertEquals("Login form", item.name)
        assertEquals("<FrameLayout/>", c.readText(item))

        val png = tmp.newFile("logo.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val image = c.addFile(CollectionKind.Images, png)
        assertEquals("logo.png", image.name)
        assertEquals("logo2.png", c.rename(image, "logo2").name)
        assertTrue(c.delete(c.list(CollectionKind.Images).single()))
        assertTrue(c.list(CollectionKind.Images).isEmpty())
    }

    @Test fun refusesNamesThatEscape() {
        val c = CollectionStore(tmp.newFolder("collection"))
        listOf("../x", "a/b", "", " lead", ".").forEach { n ->
            assertFalse(n, c.isValidName(n))
            try { c.saveText(CollectionKind.Blocks, n, "{}"); fail(n) } catch (_: IllegalArgumentException) {}
        }
    }
}
