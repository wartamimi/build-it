package dev.buildit.engine.build

import dev.buildit.engine.build.res.ArscReader
import dev.buildit.engine.build.res.FrameworkResources
import dev.buildit.engine.build.res.Res
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLClassLoader
import java.util.zip.ZipFile

class FrameworkResourcesTest {
    @Test fun readsAttributesIdsAndSymbolsFromAndroidJar() {
        val jar = TestSdk.androidJar
        TestSdk.require(jar != null, "android.jar")
        val fw = FrameworkResources.load(jar!!)
        // Oracle: the constants in android.jar's own R classes.
        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), null)
        fun r(type: String, name: String) = loader.loadClass("android.R\$$type").getField(name).getInt(null)

        listOf("orientation", "layout_width", "text", "textSize", "gravity", "id", "theme", "label").forEach { n ->
            assertEquals(n, r("attr", n), fw.attr(n)!!.id)
            assertTrue(n, fw.isPublic(fw.attr(n)!!.id))
        }
        assertEquals(r("style", "Theme_Material_Light_DarkActionBar"), fw.id("style", "Theme.Material.Light.DarkActionBar"))
        assertEquals(r("color", "white"), fw.id("color", "white"))

        val orientation = fw.attr("orientation")!!
        assertTrue(orientation.format and Res.FORMAT_ENUM != 0)
        assertEquals(1, orientation.symbols["vertical"])
        val width = fw.attr("layout_width")!!
        assertEquals(-1, width.symbols["match_parent"])
        assertEquals(-2, width.symbols["wrap_content"])
        assertTrue(width.format and Res.FORMAT_DIMENSION != 0)
        val gravity = fw.attr("gravity")!!
        assertTrue(gravity.format and Res.FORMAT_FLAGS != 0)
        assertEquals(0x11, gravity.symbols["center"])
        assertNotNull(fw.attr("textSize"))
        assertTrue(fw.attr("textSize")!!.format and Res.FORMAT_DIMENSION != 0)

        // Private framework resources are known but not public.
        val pkg = ArscReader.read(ZipFile(jar).use { z -> z.getInputStream(z.getEntry("resources.arsc")).readBytes() }).packages.first { it.id == 0x01 }
        val private = pkg.entries.first { (pkg.specFlags[it.id] ?: 0) and Res.SPEC_PUBLIC == 0 }
        assertEquals(private.id, fw.id(private.type, private.name))
        assertFalse(fw.isPublic(private.id))
    }
}
