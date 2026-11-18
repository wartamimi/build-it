package dev.buildit.engine.build

import dev.buildit.engine.deps.MavenCache
import dev.buildit.engine.deps.Repository
import dev.buildit.engine.project.ProjectStore
import dev.buildit.engine.project.ProjectTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Kotlin projects through the real pipeline: the compiler (kotlin-compiler-embeddable) is
 * downloaded from Maven Central like a library and loaded through [JvmKotlinCompilerHost].
 */
class KotlinBuildTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun maven(): MavenCache {
        val repos = System.getenv("BUILDIT_TEST_MAVEN")?.let { listOf(Repository("Test mirror", it.trimEnd('/') + "/")) } ?: Repository.defaults
        return MavenCache(File(System.getProperty("java.io.tmpdir"), "buildit-test-maven"), repos)
    }

    private fun request(dir: File, app: dev.buildit.engine.project.AppConfig): BuildRequest {
        val jar = TestSdk.androidJar
        TestSdk.require(jar != null, "android.jar")
        return BuildRequest(dir, app, jar!!, BuildType.Debug, key = { Keys.debugKey(File(tmp.root, "debug.p12")) },
            toolCache = File(tmp.root, "tools"), maven = maven(), kotlin = JvmKotlinCompilerHost)
    }

    @Test fun mixedKotlinAndJavaProjectBuilds() {
        val p = ProjectStore(tmp.newFolder("ws")).create("Kt", "com.example.kt", ProjectTemplates.EmptyActivityKotlin)
        val src = File(p.directory, "app/src/main/kotlin/com/example/kt")
        File(src, "MainActivity.kt").writeText(
            """
            package com.example.kt

            import android.app.Activity
            import android.os.Bundle
            import android.widget.TextView

            data class Greeting(val name: String, val times: Int)

            class MainActivity : Activity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(R.layout.activity_main)
                    val g = Greeting(Helper.name(), 2)
                    title = (1..g.times).joinToString(" ") { "Hi ${'$'}{g.name}" }
                    findViewById<TextView?>(android.R.id.text1)?.setOnClickListener { finish() }
                }
            }
            """.trimIndent(),
        )
        // Java calling Kotlin and Kotlin calling Java
        File(src, "Helper.java").writeText(
            """
            package com.example.kt;

            public final class Helper {
                public static String name() { return new Greeting("Build It", 1).getName(); }
            }
            """.trimIndent(),
        )
        val result = BuildPipeline().run(request(p.directory, p.manifest.app))
        assertTrue(result.diagnostics.joinToString("\n"), result.success)

        val buildTools = File(TestSdk.root, "build-tools").listFiles().orEmpty().sortedDescending().firstOrNull()
        TestSdk.require(buildTools != null, "build-tools")
        val dex = TestSdk.run(File(buildTools, "dexdump").path, result.apk!!.path)
        assertTrue(dex.contains("Lcom/example/kt/MainActivity;"))
        assertTrue(dex.contains("Lcom/example/kt/Greeting;"))
        assertTrue(dex.contains("Lcom/example/kt/Helper;"))
        // The Kotlin standard library is packaged for run time
        assertTrue(dex.contains("Lkotlin/collections/CollectionsKt"))
    }

    @Test fun kotlinErrorsPointToTheSourceLine() {
        val p = ProjectStore(tmp.newFolder("ws")).create("Kt", "com.example.kt", ProjectTemplates.EmptyActivityKotlin)
        val file = File(p.directory, "app/src/main/kotlin/com/example/kt/MainActivity.kt")
        file.writeText(file.readText().replace("setContentView(R.layout.activity_main)", "setContentView(R.layout.activity_main)\n        val n: Int = \"text\""))
        val result = BuildPipeline().run(request(p.directory, p.manifest.app))
        assertFalse(result.success)
        assertEquals(BuildStep.Compile, result.failedStep)
        val e = result.diagnostics.first { it.severity == BuildDiagnostic.Severity.Error }
        assertEquals("app/src/main/kotlin/com/example/kt/MainActivity.kt", e.file)
        assertEquals(file.readLines().indexOfFirst { it.contains("val n: Int") } + 1, e.line)
        assertTrue(e.message, e.message.contains("String") || e.message.contains("mismatch"))
    }
}
