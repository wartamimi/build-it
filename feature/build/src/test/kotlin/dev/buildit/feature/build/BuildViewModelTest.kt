package dev.buildit.feature.build

import dev.buildit.core.common.DispatcherProvider
import dev.buildit.core.data.OpenProject
import dev.buildit.core.data.ProjectRepository
import dev.buildit.core.model.ProjectSummary
import dev.buildit.engine.build.BuildStep
import dev.buildit.engine.build.Downloader
import dev.buildit.engine.build.Toolchain
import dev.buildit.engine.project.LoadResult
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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class BuildViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var project: LoadResult.Loaded

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        project = ProjectStore(tmp.newFolder("ws")).create("Demo", "com.example.demo")
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private val unimplemented = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ProjectRepository::class.java)) { _, m, _ -> error("not used: ${m.name}") } as ProjectRepository

    private inner class Repo : ProjectRepository by unimplemented {
        override suspend fun open(id: String) = OpenProject(
            ProjectSummary(project.manifest.id, "Demo", "com.example.demo", project.directory.path, 0, 0, null), project.manifest, project.directory,
        )
    }

    private val dispatchers = object : DispatcherProvider {
        override val io = dispatcher
        override val default = dispatcher
        override val main = dispatcher
    }

    private fun vm(toolchain: Toolchain) = BuildViewModel("p", Repo(), toolchain, File(tmp.root, "keys/debug.p12"), dispatchers)

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun platformIsDownloadedOnlyAfterTheLicenseIsAccepted() = runTest(dispatcher) {
        val zip = ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z -> z.putNextEntry(ZipEntry("android-36/android.jar")); z.write("jar".toByteArray()); z.closeEntry() }
        }.toByteArray()
        val index = """
            <?xml version="1.0"?>
            <sdk:sdk-repository xmlns:sdk="x" xmlns:xsi="y">
              <license id="android-sdk-license" type="text">Terms and Conditions</license>
              <channel id="channel-0">stable</channel>
              <remotePackage path="platforms;android-36">
                <revision><major>2</major></revision>
                <display-name>Android SDK Platform 36</display-name>
                <uses-license ref="android-sdk-license"/>
                <channelRef ref="channel-0"/>
                <archives><archive><complete><size>${zip.size}</size><checksum type="sha1">${sha1(zip)}</checksum><url>platform-36_r02.zip</url></complete></archive></archives>
              </remotePackage>
            </sdk:sdk-repository>
        """.trimIndent()
        val requests = mutableListOf<String>()
        val toolchain = Toolchain(tmp.newFolder("tc"), Downloader { url ->
            requests += url
            val bytes = if (url.endsWith(".xml")) index.toByteArray() else zip
            bytes.inputStream() to bytes.size.toLong()
        })
        val vm = vm(toolchain)
        advanceUntilIdle()
        assertEquals(PlatformState.Missing(36), vm.state.value.platform)

        vm.requestPlatform()
        advanceUntilIdle()
        val license = vm.state.value.platform as PlatformState.License
        assertEquals("Terms and Conditions", license.text)
        assertEquals(1, requests.size) // only the index so far

        vm.acceptLicense()
        advanceUntilIdle()
        assertEquals(PlatformState.Ready(36), vm.state.value.platform)
        assertEquals("jar", toolchain.androidJar(36)!!.readText())
        assertTrue(toolchain.isLicenseAccepted("android-sdk-license", "Terms and Conditions"))
    }

    /** A toolchain whose API 36 platform is the local SDK's android.jar (linked, not copied). */
    private fun sdkToolchain(): Toolchain {
        val jar = sdkAndroidJar()
        if (System.getenv("CI") == "true") assertTrue("android.jar is required on CI", jar != null) else assumeTrue("android.jar not found", jar != null)
        val dir = tmp.newFolder("tc")
        File(dir, "platforms/android-36").mkdirs()
        Files.createSymbolicLink(File(dir, "platforms/android-36/android.jar").toPath(), jar!!.toPath())
        return Toolchain(dir)
    }

    @Test fun buildProducesASignedApkWithEveryStepDone() = runTest(dispatcher) {
        val vm = vm(sdkToolchain())
        advanceUntilIdle()
        assertEquals(PlatformState.Ready(36), vm.state.value.platform)
        vm.build()
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.diagnostics.joinToString("\n"), s.apk != null)
        assertTrue(s.apk!!.file.isFile)
        assertEquals(BuildStep.entries.map { StepState.Done }, s.steps.map { it.state })
        assertEquals(false, s.running)
    }

    @Test fun failedBuildMarksTheStepAndListsTheError() = runTest(dispatcher) {
        val src = File(project.directory, "app/src/main/java/com/example/demo/MainActivity.java")
        src.writeText(src.readText().replace("super.onCreate(savedInstanceState);", "super.onCreate(savedInstanceState); missing();"))
        val vm = vm(sdkToolchain())
        advanceUntilIdle()
        vm.build()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(BuildStep.Compile, s.failedStep)
        assertEquals(StepState.Failed, s.steps.single { it.step == BuildStep.Compile }.state)
        val e = s.diagnostics.first()
        assertEquals("app/src/main/java/com/example/demo/MainActivity.java", e.file)
        assertEquals(9, e.line)
    }

    private fun sdkAndroidJar(): File? {
        val roots = listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT")).toMutableList()
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val lp = File(dir, "local.properties")
            if (lp.isFile) Properties().apply { lp.inputStream().use(::load) }.getProperty("sdk.dir")?.let(roots::add)
            dir = dir.parentFile
        }
        return roots.map { File(it, "platforms/android-36/android.jar") }.firstOrNull { it.isFile }
    }
}
