package dev.buildit.feature.libraries

import dev.buildit.core.common.DispatcherProvider
import dev.buildit.core.data.OpenProject
import dev.buildit.core.data.ProjectRepository
import dev.buildit.core.data.SaveResult
import dev.buildit.core.model.ProjectSummary
import dev.buildit.engine.deps.Http
import dev.buildit.engine.deps.LibrarySearch
import dev.buildit.engine.deps.MavenCache
import dev.buildit.engine.deps.Repository
import dev.buildit.engine.project.AppCompat
import dev.buildit.engine.project.AppCompatSources
import dev.buildit.engine.project.LoadResult
import dev.buildit.engine.project.ManifestSync
import dev.buildit.engine.project.ProjectManifest
import dev.buildit.engine.project.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class LibrariesViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var project: LoadResult.Loaded
    private lateinit var manifest: ProjectManifest

    /** A tiny Maven repository: a:1.0 depends on b:2.0; c is missing. */
    private val repo = Repository("Fake", "https://repo.test/")
    private val files = mapOf(
        "com/example/a/1.0/a-1.0.pom" to pom("a", "1.0", "<dependency><groupId>com.example</groupId><artifactId>b</artifactId><version>2.0</version></dependency>"),
        "com/example/a/1.0/a-1.0.jar" to "jar-a",
        "com/example/b/2.0/b-2.0.pom" to pom("b", "2.0", ""),
        "com/example/b/2.0/b-2.0.jar" to "jar-b",
        "com/example/a/maven-metadata.xml" to "<metadata><versioning><versions><version>0.9</version><version>1.0</version><version>1.1-beta01</version></versions></versioning></metadata>",
    )
    private val http = Http { url -> files[url.removePrefix(repo.url)]?.toByteArray() }

    private fun pom(a: String, v: String, deps: String) =
        "<project><groupId>com.example</groupId><artifactId>$a</artifactId><version>$v</version><dependencies>$deps</dependencies></project>"

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        project = ProjectStore(tmp.newFolder("ws")).create("Demo", "com.example.demo")
        manifest = project.manifest
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private val unimplemented = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ProjectRepository::class.java)) { _, m, _ -> error("not used: ${m.name}") } as ProjectRepository

    private inner class Repo : ProjectRepository by unimplemented {
        override suspend fun open(id: String) = OpenProject(
            ProjectSummary(manifest.id, "Demo", "com.example.demo", project.directory.path, 0, 0, null), manifest, project.directory,
        )
        override suspend fun saveSettings(id: String, manifest: ProjectManifest, force: Boolean): SaveResult {
            this@LibrariesViewModelTest.manifest = manifest
            return SaveResult(manifest, ManifestSync.apply(project.directory, manifest, force).conflicts)
        }
    }

    private val dispatchers = object : DispatcherProvider {
        override val io = dispatcher
        override val default = dispatcher
        override val main = dispatcher
    }

    private fun vm() = LibrariesViewModel("id", Repo(), MavenCache(File(tmp.root, "m2").apply { mkdirs() }, listOf(repo), http), dispatchers, LibrarySearch(http))

    @Test fun addingALibraryDownloadsItsDependenciesAndUpdatesTheProject() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.choose("com.example", "a")
        advanceUntilIdle()
        assertEquals(listOf("1.1-beta01", "1.0", "0.9"), vm.state.value.choice!!.versions)
        vm.add("com.example", "a", "1.0")
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(listOf("com.example:a:1.0"), manifest.app.dependencies.map { it.coordinate })
        assertEquals(listOf("com.example:a:1.0", "com.example:b:2.0"), s.included.map { it.coordinate })
        assertEquals(listOf("com.example:a"), s.included.single { it.coordinate.endsWith("b:2.0") }.requestedBy)
        assertTrue(s.message is LibrariesMessage.Added)
        val gradle = File(project.directory, "app/build.gradle.kts").readText()
        assertTrue(gradle, gradle.contains("implementation(\"com.example:a:1.0\")"))

        vm.setEnabled("com.example:a", false)
        advanceUntilIdle()
        assertEquals(false, manifest.app.dependencies.single().enabled)
        assertTrue(!File(project.directory, "app/build.gradle.kts").readText().contains("com.example:a"))

        vm.remove("com.example:a")
        advanceUntilIdle()
        assertTrue(manifest.app.dependencies.isEmpty())
        assertTrue(vm.state.value.included.isEmpty())
    }

    @Test fun aLibraryThatCannotBeDownloadedLeavesTheProjectUnchanged() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.add("com.example", "c", "1.0")
        advanceUntilIdle()
        assertTrue(manifest.app.dependencies.isEmpty())
        assertTrue(vm.state.value.problems.toString(), vm.state.value.problems.any { it.contains("com.example:c") })
    }

    @Test fun appCompatSwitch() = runTest(dispatcher) {
        // On: the libraries cannot be downloaded from this repository, so nothing changes
        val vm = vm()
        advanceUntilIdle()
        vm.setAppCompat(true)
        advanceUntilIdle()
        assertEquals(false, manifest.app.appCompat)
        assertTrue(manifest.app.dependencies.isEmpty())
        assertTrue(vm.state.value.problems.isNotEmpty())

        // Off from on: libraries turned off, theme and the untouched screen go back
        manifest = manifest.copy(app = AppCompat.set(manifest.app, true))
        ManifestSync.apply(project.directory, manifest)
        AppCompatSources.sync(project.directory, manifest)
        val vm2 = vm()
        advanceUntilIdle()
        assertTrue(vm2.state.value.appCompat)
        vm2.dismissMessage()
        vm2.setAppCompat(false)
        advanceUntilIdle()
        assertEquals(false, manifest.app.appCompat)
        assertTrue(manifest.app.dependencies.none { it.enabled })
        assertEquals(LibrariesMessage.AppCompatChanged(false, listOf("MainActivity"), emptyList(), emptyList()), vm2.state.value.message)
        assertTrue(File(project.directory, "app/src/main/java/com/example/demo/MainActivity.java").readText().contains("extends Activity {"))
        assertTrue(File(project.directory, ManifestSync.THEMES).readText().contains("android:Theme.Material.Light.DarkActionBar"))
    }
}
