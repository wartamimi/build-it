package dev.buildit.engine.build

import dev.buildit.engine.build.res.ArscReader
import dev.buildit.engine.build.res.FrameworkResources
import dev.buildit.engine.build.res.ManifestOptions
import dev.buildit.engine.build.res.Res
import dev.buildit.engine.build.res.ResourceCompiler
import dev.buildit.engine.project.ProjectStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ResourceCompilerTest {
    @get:Rule val tmp = TemporaryFolder()

    companion object {
        private var fw: FrameworkResources? = null

        @BeforeClass @JvmStatic fun load() { TestSdk.androidJar?.let { fw = FrameworkResources.load(it) } }
    }

    private val options = ManifestOptions("com.example.demo", "com.example.demo", 3, "1.2", 26, 36, debuggable = true)

    private fun project(): File {
        TestSdk.require(fw != null, "android.jar")
        return ProjectStore(tmp.newFolder("ws")).create("Demo", "com.example.demo").directory
    }

    private fun compile(dir: File, d: Diagnostics = Diagnostics()) =
        ResourceCompiler(fw!!, d) { it.relativeTo(dir).path }.compile(File(dir, "app/src/main/res"), File(dir, "app/src/main/AndroidManifest.xml"), options)

    private fun apk(r: dev.buildit.engine.build.res.CompiledResources): File {
        val f = tmp.newFile("res.apk")
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            put("AndroidManifest.xml", r.manifest); put("resources.arsc", r.table)
            r.files.forEach { (n, b) -> put(n, b) }
        }
        return f
    }

    @Test fun templateProjectCompilesAndAapt2ReadsIt() {
        val dir = project()
        val d = Diagnostics()
        val r = compile(dir, d)
        assertNotNull(d.all.toString(), r)
        r!!
        val table = ArscReader.read(r.table).packages.single()
        val names = table.entries.map { "${it.type}/${it.name}" }.toSet()
        assertTrue(names.toString(), names.containsAll(listOf("string/app_name", "string/hello", "layout/activity_main", "style/AppTheme", "id/greeting")))
        val style = table.entries.single { it.name == "AppTheme" }
        assertEquals(fw!!.id("style", "Theme.Material.Light.DarkActionBar"), style.parent)
        assertTrue(r.rJava, r.rJava.contains("public static final int activity_main = 0x7f"))

        val aapt2 = TestSdk.aapt2
        TestSdk.require(aapt2 != null, "aapt2")
        val apk = apk(r)
        val badging = TestSdk.run(aapt2!!.path, "dump", "badging", apk.path)
        assertTrue(badging, badging.contains("package: name='com.example.demo' versionCode='3' versionName='1.2'"))
        assertTrue(badging, badging.contains("minSdkVersion:'26'"))
        assertTrue(badging, badging.contains("targetSdkVersion:'36'"))
        assertTrue(badging, badging.contains("application-label:'Demo'"))
        assertTrue(badging, badging.contains("launchable-activity: name='com.example.demo.MainActivity'"))
        assertTrue(badging, badging.contains("application-debuggable"))

        val layout = TestSdk.run(aapt2.path, "dump", "xmltree", "--file", "res/layout/activity_main.xml", apk.path)
        assertTrue(layout, layout.contains("A: http://schemas.android.com/apk/res/android:orientation(0x010100c4)=1"))
        assertTrue(layout, layout.contains("A: http://schemas.android.com/apk/res/android:layout_width(0x010100f4)=-1"))
        assertTrue(layout, layout.contains("A: http://schemas.android.com/apk/res/android:gravity(0x010100af)=0x00000011"))
        assertTrue(layout, layout.contains("android:padding(0x010100d5)=16.000000dp"))
        assertTrue(layout, layout.contains("android:textSize(0x01010095)=20.000000sp"))
        assertTrue(layout, layout.contains("android:text(0x0101014f)=@0x7f"))

        val resources = TestSdk.run(aapt2.path, "dump", "resources", apk.path)
        assertTrue(resources, resources.contains("resource 0x7f") && resources.contains("string/hello"))
        assertTrue(resources, resources.contains("\"Hello from Build It\""))
    }

    @Test fun valuesQualifiersAndArraysCompile() {
        val dir = project()
        val res = File(dir, "app/src/main/res")
        File(res, "values/extra.xml").writeText(
            """
            <resources>
                <color name="accent">#FF6600</color>
                <color name="ref">@color/accent</color>
                <dimen name="gap">  12.5dp </dimen>
                <bool name="flag">true</bool>
                <integer name="count">0x10</integer>
                <string name="quoted">"  keep  spaces  "</string>
                <string name="escaped">It\'s a\nnew   line</string>
                <string-array name="planets"><item>Mercury</item><item>@string/hello</item></string-array>
                <integer-array name="numbers"><item>1</item><item>2</item></integer-array>
                <plurals name="songs"><item quantity="one">%d song</item><item quantity="other">%d songs</item></plurals>
                <item type="id" name="spare" />
                <style name="AppTheme.Dark" ><item name="android:windowNoTitle">true</item><item name="android:colorAccent">@color/accent</item></style>
            </resources>
            """.trimIndent(),
        )
        File(res, "values-in").mkdirs()
        File(res, "values-in/strings.xml").writeText("<resources><string name=\"hello\">Halo dari Build It</string></resources>")
        File(res, "values-night-v26").mkdirs()
        File(res, "values-night-v26/colors.xml").writeText("<resources><color name=\"accent\">#80112233</color></resources>")
        val d = Diagnostics()
        val r = compile(dir, d)
        assertNotNull(d.all.toString(), r)
        val pkg = ArscReader.read(r!!.table).packages.single()
        fun entries(name: String) = pkg.entries.filter { it.name == name }
        assertEquals(Res.TYPE_INT_COLOR_RGB8, entries("accent").first { it.config[8].toInt() == 0 && it.config[29].toInt() == 0 }.value!!.type)
        assertEquals(2, entries("accent").size)
        assertEquals(2, entries("hello").size)
        assertEquals(Res.TYPE_INT_HEX, entries("count").single().value!!.type)
        assertEquals(Res.TYPE_DIMENSION, entries("gap").single().value!!.type)
        val planets = entries("planets").single().bag!!
        assertEquals(listOf(Res.ATTR_MIN, Res.ATTR_MIN + 1), planets.map { it.key })
        assertEquals(Res.TYPE_REFERENCE, planets[1].value.type)
        assertEquals(listOf(Res.ATTR_OTHER, Res.ATTR_ONE), entries("songs").single().bag!!.map { it.key })
        // Implicit parent from the dotted name; items sorted by attribute id.
        val dark = entries("AppTheme.Dark").single()
        assertEquals(r.ids["style/AppTheme"], dark.parent)
        assertEquals(dark.bag!!.map { it.key }.sortedWith { a, b -> Integer.compareUnsigned(a, b) }, dark.bag!!.map { it.key })
        assertTrue(r.ids.containsKey("id/spare"))

        val table = ArscReader.read(r.table)
        fun str(name: String) = table.strings[entries(name).single().value!!.data]
        assertEquals("  keep  spaces  ", str("quoted"))
        assertEquals("It's a\nnew line", str("escaped"))
    }

    @Test fun errorsPointToFileAndLine() {
        val dir = project()
        val layout = File(dir, "app/src/main/res/layout/activity_main.xml")
        layout.writeText(
            layout.readText()
                .replace("android:orientation=\"vertical\"", "android:orientation=\"diagonal\"")
                .replace("@string/hello", "@string/missing"),
        )
        File(dir, "app/src/main/res/layout/second.xml").writeText(
            """
            <androidx.recyclerview.widget.RecyclerView xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto"
                android:layout_width="match_parent" android:layout_height="match_parent" app:layoutManager="x" />
            """.trimIndent(),
        )
        val d = Diagnostics()
        assertNull(compile(dir, d))
        val errors = d.all.map { it.toString() }
        assertTrue(errors.toString(), errors.any { it.startsWith("app/src/main/res/layout/activity_main.xml:2:") && it.contains("'diagonal'") && it.contains("vertical") })
        assertTrue(errors.toString(), errors.any { it.contains("activity_main.xml:9:") && it.contains("string/missing does not exist") })
        assertTrue(errors.toString(), errors.any { it.contains("second.xml:1:") && it.contains("androidx.recyclerview:recyclerview") })
        assertTrue(errors.toString(), errors.any { it.contains("second.xml") && it.contains("layoutManager") })
    }
}
