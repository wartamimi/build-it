package dev.buildit.engine.blocks

import dev.buildit.engine.project.SourceLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties
import javax.tools.ToolProvider

class CodegenTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun generate(language: SourceLanguage): String {
        val r = BlockSync.generateCode(manifest(language), sampleDocument(), ctx)
        assertTrue(r.toString(), r is BlockSync.Result.Generated)
        return (r as BlockSync.Result.Generated).code
    }

    @Test fun javaOutputIsDeterministicAndComplete() {
        val code = generate(SourceLanguage.Java)
        assertEquals(code, generate(SourceLanguage.Java))
        listOf(
            "package com.example.demo;",
            "public class MainActivity extends Activity {",
            "private double count = 0;",
            "private ArrayList<String> items = new ArrayList<>();",
            "private TextView greeting;",
            "setContentView(R.layout.activity_main);",
            "button1.setOnClickListener(v -> onButton1Click());",
            "greeting.setText(\"Count: \" + formatNumber(count));",
            "for (int i1 = 0; i1 < (int) 3.0; i1++) {",
            "name = \"Ada \\\"$\\\" \\\\ \\n\";",
            "if ((count >= 5.0) && !done) {",
            "} else {",
            "startActivity(new Intent(this, SettingsActivity.class));",
            "photo.setVisibility(View.GONE);",
            "private void runReset() {",
            "protected void onPause() {",
            "private final Handler ticker = new Handler(Looper.getMainLooper());",
            "prefs = getSharedPreferences(\"prefs\", MODE_PRIVATE);",
            "startTicker((long) 1000.0, true);",
            "private void onTickerTick() {",
            "prefs.edit().putString(\"last\", formatNumber(count)).apply();",
            "setMessage(prefs.getString(\"last\", \"\"))",
            "private void onConfirmOk() {",
            "playPlayer(R.raw.ding);",
            "private void onPlayerDone() {",
            "startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(\"https://example.com\")));",
            "ticker.removeCallbacksAndMessages(null);\n        stopPlayer();\n        super.onDestroy();",
        ).forEach { assertTrue("missing: $it\n$code", code.contains(it)) }
    }

    @Test fun kotlinOutput() {
        val code = generate(SourceLanguage.Kotlin)
        listOf(
            "package com.example.demo",
            "class MainActivity : Activity() {",
            "private var count: Double = 0.0",
            "private val items: ArrayList<String> = ArrayList()",
            "private lateinit var greeting: TextView",
            "button1.setOnClickListener { onButton1Click() }",
            "for (i1 in 0 until 3.0.toInt()) {",
            "name = \"Ada \\\"\\$\\\" \\\\ \\n\"",
            "greeting.text = \"Count: \" + formatNumber(count)",
            "startActivity(Intent(this, SettingsActivity::class.java))",
            "photo.visibility = View.GONE",
            "count += 1.0",
            "private val ticker = Handler(Looper.getMainLooper())",
            "private lateinit var prefs: SharedPreferences",
            "private var player: MediaPlayer? = null",
            "startTicker(1000.0.toLong(), true)",
            "setPositiveButton(android.R.string.ok) { _, _ -> onConfirmOk() }",
            "(prefs.getString(\"last\", \"\") ?: \"\")",
            "val player = MediaPlayer.create(this, sound) ?: return",
        ).forEach { assertTrue("missing: $it\n$code", code.contains(it)) }
    }

    /** Compiles the generated Java against the real android.jar, so the output is known to build. */
    @Test fun generatedJavaCompilesAgainstAndroidJar() {
        val androidJar = findAndroidJar()
        assumeTrue("android.jar not found (set ANDROID_HOME)", androidJar != null)
        val compiler = ToolProvider.getSystemJavaCompiler()
        assumeTrue("no system Java compiler", compiler != null)
        val src = tmp.newFolder("src")
        val pkg = File(src, "com/example/demo").apply { mkdirs() }
        File(pkg, "MainActivity.java").writeText(generate(SourceLanguage.Java))
        File(pkg, "SettingsActivity.java").writeText("package com.example.demo; public class SettingsActivity extends android.app.Activity {}")
        File(pkg, "R.java").writeText(
            """
            package com.example.demo;
            public final class R {
                public static final class layout { public static final int activity_main = 1; }
                public static final class raw { public static final int ding = 6; }
                public static final class id { public static final int greeting = 2, button1 = 3, agree = 4, photo = 5; }
            }
            """.trimIndent(),
        )
        val out = tmp.newFolder("out")
        val files = src.walk().filter { it.isFile }.map { it.path }.toList()
        val errors = java.io.ByteArrayOutputStream()
        val rc = compiler!!.run(null, null, errors, "-classpath", androidJar!!.path, "-d", out.path, "--release", "17", *files.toTypedArray())
        assertEquals(errors.toString(), 0, rc)
        assertTrue(File(out, "com/example/demo/MainActivity.class").isFile)
    }

    @Test fun writeReplacesPristineTemplateButNotHandEdits() {
        val dir = tmp.newFolder("proj")
        val m = manifest(SourceLanguage.Java)
        val path = BlockSync.sourcePath(m, "MainActivity")
        val file = File(dir, path).apply { parentFile.mkdirs(); writeText(BlockSync.pristineSource(m, "MainActivity")!!) }
        val first = BlockSync.write(dir, m, sampleDocument(), ctx)
        assertTrue(first is BlockSync.Result.Generated && first.written)
        file.appendText("// my edit\n")
        val second = BlockSync.write(dir, m, sampleDocument(), ctx)
        assertTrue(second is BlockSync.Result.UserModified)
        assertTrue(file.readText().endsWith("// my edit\n"))
        assertTrue(BlockSync.write(dir, m, sampleDocument(), ctx, force = true) is BlockSync.Result.Generated)
    }

    @Test fun invalidDocumentsGenerateNothing() {
        val bad = sampleDocument().let { d -> BlockOps.setField(d, "s2", "view", "nope") }
        assertTrue(BlockSync.generateCode(manifest(SourceLanguage.Java), bad, ctx) is BlockSync.Result.Invalid)
    }

    @Test fun jsonRoundTrip() {
        val store = BlockStore(tmp.root)
        val doc = sampleDocument()
        store.save(doc)
        assertEquals(doc, store.load("MainActivity") { "x" })
        assertEquals("event_create", store.load("Other") { "id1" }.stacks.single().blocks.single().type)
    }

    private fun findAndroidJar(): File? {
        val roots = listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT")).toMutableList()
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val lp = File(dir, "local.properties")
            if (lp.isFile) Properties().apply { lp.inputStream().use(::load) }.getProperty("sdk.dir")?.let(roots::add)
            dir = dir.parentFile
        }
        return roots.asSequence().map { File(it, "platforms") }.filter { it.isDirectory }
            .flatMap { p -> p.listFiles().orEmpty().sortedDescending().map { File(it, "android.jar") } }
            .firstOrNull { it.isFile }
    }
}
