package dev.buildit.engine.build

import dev.buildit.engine.project.ProjectStore
import dev.buildit.engine.project.ProjectTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

class BuildPipelineTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun request(dir: File, app: dev.buildit.engine.project.AppConfig, type: BuildType = BuildType.Debug): BuildRequest {
        val jar = TestSdk.androidJar
        TestSdk.require(jar != null, "android.jar")
        val keystore = File(tmp.root, "debug.p12")
        return BuildRequest(dir, app, jar!!, type, key = { Keys.debugKey(keystore) })
    }

    private fun javaProject(): Pair<File, dev.buildit.engine.project.AppConfig> {
        val p = ProjectStore(tmp.newFolder("ws")).create("Hello World", "com.example.hello")
        return p.directory to p.manifest.app
    }

    @Test fun templateProjectBuildsASignedApkThatTheSdkToolsAccept() {
        val (dir, app) = javaProject()
        // A richer activity: views from R.id, a lambda listener, BuildConfig and a string resource.
        File(dir, "app/src/main/java/com/example/hello/MainActivity.java").writeText(
            """
            package com.example.hello;

            import android.app.Activity;
            import android.os.Bundle;
            import android.widget.TextView;

            public class MainActivity extends Activity {
                private int taps;

                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    setContentView(R.layout.activity_main);
                    TextView greeting = findViewById(R.id.greeting);
                    greeting.setOnClickListener(v -> greeting.setText(getString(R.string.hello) + " " + (++taps)));
                    if (BuildConfig.DEBUG) setTitle(BuildConfig.VERSION_NAME);
                }
            }
            """.trimIndent(),
        )
        val events = mutableListOf<BuildEvent>()
        val result = BuildPipeline().run(request(dir, app), { events += it })
        assertTrue(result.diagnostics.joinToString("\n"), result.success)
        val apk = result.apk!!
        assertEquals(BuildStep.entries.toList(), events.filterIsInstance<BuildEvent.StepFinished>().map { it.step })

        ZipFile(apk).use { z ->
            assertNotNull(z.getEntry("classes.dex"))
            val arsc = z.getEntry("resources.arsc")
            assertEquals(java.util.zip.ZipEntry.STORED, arsc.method)
        }
        assertEquals("resources.arsc must be 4-byte aligned", 0L, dataOffset(apk, "resources.arsc") % 4)
        assertTrue(ApkSigning.verify(apk, app.minSdk).isEmpty())

        val sdk = TestSdk.root
        val buildTools = File(sdk, "build-tools").listFiles().orEmpty().sortedDescending().firstOrNull()
        TestSdk.require(buildTools != null, "build-tools")
        val badging = TestSdk.run(File(buildTools, "aapt2").path, "dump", "badging", apk.path)
        assertTrue(badging, badging.contains("package: name='com.example.hello'"))
        assertTrue(badging, badging.contains("launchable-activity: name='com.example.hello.MainActivity'"))
        val dex = TestSdk.run(File(buildTools, "dexdump").path, apk.path)
        assertTrue(dex.contains("Lcom/example/hello/MainActivity;"))
        assertTrue(dex.contains("Lcom/example/hello/R\$id;"))
        val verify = TestSdk.run(File(buildTools, "apksigner").path, "verify", "--verbose", apk.path)
        assertTrue(verify, verify.contains("Verified using v2 scheme (APK Signature Scheme v2): true"))
        assertTrue(verify, verify.contains("Verified using v3 scheme (APK Signature Scheme v3): true"))

        // The debug key is reused, so the next build has the same signer.
        val again = BuildPipeline().run(request(dir, app))
        assertTrue(again.success)
        val certs1 = TestSdk.run(File(buildTools, "apksigner").path, "verify", "--print-certs", apk.path)
        assertTrue(certs1.contains("CN=Build It Debug"))
    }

    @Test fun javaErrorsPointToTheSourceLine() {
        val (dir, app) = javaProject()
        val src = File(dir, "app/src/main/java/com/example/hello/MainActivity.java")
        src.writeText(src.readText().replace("setContentView(R.layout.activity_main);", "setContentView(R.layout.activity_main);\n        int x = \"oops\";"))
        val result = BuildPipeline().run(request(dir, app))
        assertFalse(result.success)
        assertEquals(BuildStep.Compile, result.failedStep)
        val e = result.diagnostics.single { it.severity == BuildDiagnostic.Severity.Error }
        assertEquals("app/src/main/java/com/example/hello/MainActivity.java", e.file)
        assertEquals(11, e.line)
        assertTrue(e.message, e.message.contains("String"))
    }

    @Test fun unsupportedInputsAreReportedNotSkipped() {
        val p = ProjectStore(tmp.newFolder("ws")).create("Kt", "com.example.kt", ProjectTemplates.EmptyActivityKotlin)
        val gradle = File(p.directory, "app/build.gradle.kts")
        gradle.appendText("\ndependencies {\n    implementation(\"androidx.core:core:1.13.1\")\n}\n")
        val result = BuildPipeline().run(request(p.directory, p.manifest.app))
        assertFalse(result.success)
        assertEquals(BuildStep.Check, result.failedStep)
        val messages = result.diagnostics.map { it.toString() }
        assertTrue(messages.toString(), messages.any { it.contains("MainActivity.kt") && it.contains("Kotlin") })
        assertTrue(messages.toString(), messages.any { it.contains("build.gradle.kts") && it.contains("androidx.core:core:1.13.1") })
    }

    @Test fun cancellationStopsBeforeTheNextStep() {
        val (dir, app) = javaProject()
        var steps = 0
        val result = BuildPipeline().run(request(dir, app), { if (it is BuildEvent.StepFinished) steps++ }, isCancelled = { steps >= 2 })
        assertTrue(result.cancelled)
        assertEquals(2, steps)
    }

    /** Offset of an entry's data: central directory -> local header -> name and extra lengths. */
    private fun dataOffset(apk: File, name: String): Long {
        RandomAccessFile(apk, "r").use { f ->
            fun u16(at: Long): Int { f.seek(at); return java.lang.Short.reverseBytes(f.readShort()).toInt() and 0xFFFF }
            fun u32(at: Long): Long { f.seek(at); return Integer.reverseBytes(f.readInt()).toLong() and 0xFFFFFFFFL }
            var eocd = f.length() - 22
            while (u32(eocd) != 0x06054b50L) eocd--
            val count = u16(eocd + 10)
            var p = u32(eocd + 16)
            repeat(count) {
                val nameLen = u16(p + 28); val extraLen = u16(p + 30); val commentLen = u16(p + 32)
                val local = u32(p + 42)
                val n = ByteArray(nameLen).also { f.seek(p + 46); f.readFully(it) }
                if (String(n) == name) return local + 30 + u16(local + 26) + u16(local + 28)
                p += 46 + nameLen + extraLen + commentLen
            }
        }
        error("$name not found")
    }
}
