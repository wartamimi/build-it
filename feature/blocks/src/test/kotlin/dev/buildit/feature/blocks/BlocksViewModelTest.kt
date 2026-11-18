package dev.buildit.feature.blocks

import dev.buildit.core.common.DispatcherProvider
import dev.buildit.core.data.OpenProject
import dev.buildit.core.data.ProjectRepository
import dev.buildit.core.data.SettingsRepository
import dev.buildit.core.model.ProjectSummary
import dev.buildit.core.model.UserSettings
import dev.buildit.engine.blocks.BlockOps
import dev.buildit.engine.blocks.Slot
import dev.buildit.engine.blocks.ValueType
import dev.buildit.engine.project.LoadResult
import dev.buildit.engine.project.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
class BlocksViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var project: LoadResult.Loaded

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        project = ProjectStore(tmp.root).create("Demo", "com.example.demo")
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private val unimplemented = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ProjectRepository::class.java)) { _, m, _ -> error("not used: ${m.name}") } as ProjectRepository

    private inner class Repo : ProjectRepository by unimplemented {
        override suspend fun open(id: String) = OpenProject(
            ProjectSummary(project.manifest.id, "Demo", "com.example.demo", project.directory.path, 0, 0, null), project.manifest, project.directory,
        )

        var saved: dev.buildit.engine.project.ProjectManifest? = null
        override suspend fun saveSettings(id: String, manifest: dev.buildit.engine.project.ProjectManifest, force: Boolean): dev.buildit.core.data.SaveResult {
            saved = manifest
            return dev.buildit.core.data.SaveResult(manifest, dev.buildit.engine.project.ManifestSync.apply(project.directory, manifest, force).conflicts)
        }
    }

    private class Settings(autoSave: Boolean) : SettingsRepository {
        override val settings: Flow<UserSettings> = MutableStateFlow(UserSettings(autoSave = autoSave))
        override suspend fun setThemeMode(mode: dev.buildit.core.model.ThemeMode) = Unit
        override suspend fun setDynamicColor(enabled: Boolean) = Unit
        override suspend fun setLanguage(language: dev.buildit.core.model.AppLanguage) = Unit
        override suspend fun setEditorFontSize(sp: Int) = Unit
        override suspend fun setAutoSave(enabled: Boolean) = Unit
    }

    private fun vm(autoSave: Boolean = true): BlocksViewModel {
        var n = 0
        return BlocksViewModel("p", Repo(), Settings(autoSave), object : DispatcherProvider {
            override val io = dispatcher
            override val default = dispatcher
            override val main = dispatcher
        }, ids = { "id${++n}" })
    }

    @Test fun addingBlocksAutosavesAndGeneratesJava() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("MainActivity", s.activity)
        assertEquals(listOf("greeting"), s.views.map { it.id })
        val event = s.document!!.stacks.single().blocks.single()

        vm.select(event.id)
        vm.add("view_set_text", 0f, 0f) // goes into the selected event
        advanceUntilIdle()
        val doc = vm.state.value.document!!
        assertEquals("view_set_text", doc.find(event.id)!!.statements["do"]!!.single().type)

        val source = File(project.directory, "app/src/main/java/com/example/demo/MainActivity.java")
        val text = source.readText()
        assertTrue(text, text.contains("greeting.setText(\"\");"))
        assertTrue(File(project.directory, ".buildit/blocks/MainActivity.json").isFile)
        assertEquals(CodeStatus.UpToDate, vm.state.value.codeStatus)
        assertTrue(!vm.state.value.dirty)
    }

    @Test fun handEditedSourceIsNotOverwritten() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.add("app_finish", 0f, 0f)
        advanceUntilIdle()
        val source = File(project.directory, "app/src/main/java/com/example/demo/MainActivity.java")
        source.appendText("// mine\n")
        vm.addVariable("score", ValueType.Number)
        advanceUntilIdle()
        assertTrue(vm.state.value.codeStatus is CodeStatus.HandEdited)
        assertTrue(source.readText().endsWith("// mine\n"))
        vm.save(force = true)
        advanceUntilIdle()
        assertTrue(!source.readText().contains("// mine"))
    }

    @Test fun errorsBlockGenerationAndDragCommitsAsOneUndoStep() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val before = File(project.directory, "app/src/main/java/com/example/demo/MainActivity.java").readText()
        vm.add("var_change", 10f, 10f) // no Number variable exists: invalid
        advanceUntilIdle()
        assertTrue(vm.state.value.codeStatus is CodeStatus.HasErrors)
        assertEquals(before, File(project.directory, "app/src/main/java/com/example/demo/MainActivity.java").readText())

        val original = vm.state.value.document!!
        val loose = original.stacks.last().blocks.single()
        val (base, chain) = BlockOps.detach(original, loose.id)
        val event = original.stacks.first().blocks.single()
        vm.commitDrag(original, BlockOps.insert(base, chain, Slot.Statement(event.id, "do", 0)))
        assertEquals(1, vm.state.value.document!!.stacks.size)
        vm.undo()
        assertEquals(2, vm.state.value.document!!.stacks.size)
    }

    @Test fun procedureParametersFlowIntoCallsAndDropsSnap() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val event = vm.state.value.document!!.stacks.single().blocks.single()
        vm.add("proc_define", 0f, 600f)
        val define = vm.state.value.document!!.stacks.last().blocks.single()
        assertEquals("doSomething", define.field("name"))
        vm.setField(define.id, "params", "times:number,label:text")

        // Dropped from the palette onto the event: the canvas reports the slot it snaps to
        vm.drop("proc_call", 0f, 0f) { _, _ -> Slot.Statement(event.id, "do", 0) }
        advanceUntilIdle()
        val call = vm.state.value.document!!.find(event.id)!!.statements["do"]!!.single()
        assertEquals("proc_call", call.type)
        assertEquals(setOf("arg_times", "arg_label"), call.inputs.keys)
        val java = File(project.directory, "app/src/main/java/com/example/demo/MainActivity.java").readText()
        assertTrue(java, java.contains("runDoSomething(0.0, \"\");"))

        // Renaming the procedure renames its calls; removing a parameter keeps its argument block
        vm.setField(define.id, "name", "greet")
        vm.setField(define.id, "params", "times:number")
        val doc = vm.state.value.document!!
        assertEquals("greet", doc.find(call.id)!!.field("name"))
        assertEquals(setOf("arg_times"), doc.find(call.id)!!.inputs.keys)
        assertTrue(doc.stacks.any { s -> s.blocks.single().type == "text_literal" })
    }

    @Test fun componentsAddTheirManifestPermissions() = runTest(dispatcher) {
        val repo = Repo()
        var n = 0
        val vm = BlocksViewModel("p", repo, Settings(true), object : DispatcherProvider {
            override val io = dispatcher
            override val default = dispatcher
            override val main = dispatcher
        }, ids = { "id${++n}" })
        advanceUntilIdle()
        vm.addComponent("api", dev.buildit.engine.blocks.ComponentType.Network)
        advanceUntilIdle()
        assertEquals(listOf("android.permission.INTERNET"), repo.saved!!.app.permissions)
        val manifestXml = File(project.directory, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifestXml, manifestXml.contains("<uses-permission android:name=\"android.permission.INTERNET\" />"))
        val java = File(project.directory, "app/src/main/java/com/example/demo/MainActivity.java").readText()
        assertTrue(java, java.contains("private void requestApi(final String method, final String url, final String body) {"))
    }
}
