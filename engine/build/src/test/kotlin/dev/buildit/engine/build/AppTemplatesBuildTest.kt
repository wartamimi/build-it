package dev.buildit.engine.build

import dev.buildit.engine.blocks.AppTemplates
import dev.buildit.engine.blocks.BlockStore
import dev.buildit.engine.deps.MavenCache
import dev.buildit.engine.deps.Repository
import dev.buildit.engine.project.ProjectStore
import dev.buildit.engine.project.SourceLanguage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Every app template, in Java and Kotlin, builds into an APK through the real pipeline. */
class AppTemplatesBuildTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun maven(): MavenCache {
        val repos = System.getenv("BUILDIT_TEST_MAVEN")?.let { listOf(Repository("Test mirror", it.trimEnd('/') + "/")) } ?: Repository.defaults
        return MavenCache(File(System.getProperty("java.io.tmpdir"), "buildit-test-maven"), repos)
    }

    private fun buildAll(language: SourceLanguage) {
        val jar = TestSdk.androidJar
        TestSdk.require(jar != null, "android.jar")
        val store = ProjectStore(tmp.newFolder("ws-$language"))
        AppTemplates.Kind.entries.forEach { kind ->
            val p = store.create("${kind.name} app", "com.example.${kind.id}", AppTemplates.of(kind, language))
            assertTrue(BlockStore(p.directory).exists("MainActivity"))
            val result = BuildPipeline().run(
                BuildRequest(p.directory, p.manifest.app, jar!!, BuildType.Debug, key = { Keys.debugKey(File(tmp.root, "debug.p12")) },
                    toolCache = File(tmp.root, "tools"), maven = maven(), kotlin = JvmKotlinCompilerHost),
            )
            assertTrue("$kind/$language: " + result.diagnostics.joinToString { "${it.file}:${it.line} ${it.message}" }, result.success)
        }
    }

    @Test fun javaTemplatesBuild() = buildAll(SourceLanguage.Java)
    @Test fun kotlinTemplatesBuild() = buildAll(SourceLanguage.Kotlin)
}
