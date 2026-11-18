package dev.buildit.engine.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectAssetsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun assets() = ProjectAssets(tmp.root)

    private fun code(block: () -> Unit): String = try { block(); "" } catch (e: AssetException) { e.code }

    @Test fun importListsRenamesAndDeletes() {
        val a = assets()
        val img = a.import(AssetKind.Image, "logo", "PNG", byteArrayOf(1, 2, 3))
        assertEquals(File(tmp.root, "app/src/main/res/drawable/logo.png"), img.file)
        a.import(AssetKind.Sound, "ding", "mp3", byteArrayOf(4))
        a.import(AssetKind.Font, "title_font", "ttf", byteArrayOf(5))
        assertEquals(listOf("logo"), a.list(AssetKind.Image).map { it.name })
        assertEquals(listOf("ding"), a.list(AssetKind.Sound).map { it.name })
        assertEquals(listOf("title_font"), a.list(AssetKind.Font).map { it.name })

        val renamed = a.rename(img, "brand")
        assertTrue(File(tmp.root, "app/src/main/res/drawable/brand.png").isFile)
        a.delete(renamed)
        assertTrue(a.list(AssetKind.Image).isEmpty())
    }

    @Test fun neverReplacesAndRejectsBadInput() {
        val a = assets()
        a.import(AssetKind.Image, "logo", "png", byteArrayOf(1))
        // Same resource name with another extension would be a duplicate resource.
        assertEquals("exists", code { a.import(AssetKind.Image, "logo", "webp", byteArrayOf(2)) })
        assertEquals(1, File(tmp.root, "app/src/main/res/drawable/logo.png").readBytes().single().toInt())
        assertEquals("invalid_name", code { a.import(AssetKind.Image, "Logo", "png", byteArrayOf(1)) })
        assertEquals("invalid_name", code { a.import(AssetKind.Image, "class", "png", byteArrayOf(1)) })
        assertEquals("unsupported", code { a.import(AssetKind.Sound, "x", "exe", byteArrayOf(1)) })
        assertEquals("unsupported", code { a.import(AssetKind.Image, "x", "xml", "<resources/>".toByteArray()) })
        a.import(AssetKind.Image, "star", "xml", "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"/>".toByteArray())
        assertFalse(ProjectAssets.isValidName("9patch"))
    }

    @Test fun suggestsValidNames() {
        assertEquals("my_photo_1", ProjectAssets.suggestName("My Photo (1).PNG"))
        assertEquals("res_123", ProjectAssets.suggestName("123.mp3"))
        assertEquals("bg", ProjectAssets.suggestName("bg.9.png".substringBefore('.') + ".png"))
        assertEquals("class_res", ProjectAssets.suggestName("class.ttf"))
        assertTrue(ProjectAssets.isValidName(ProjectAssets.suggestName("Ünïcode name!.otf")))
    }
}
