package dev.buildit.feature.code

import dev.buildit.core.common.DispatcherProvider
import dev.buildit.core.data.OpenProject
import dev.buildit.core.data.ProjectRepository
import dev.buildit.core.data.SettingsRepository
import dev.buildit.core.model.ProjectSummary
import dev.buildit.core.model.UserSettings
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class CodeViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var project: LoadResult.Loaded
    private val activity = "app/src/main/java/com/example/demo/MainActivity.java"

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
    }

    private class Settings(autoSave: Boolean) : SettingsRepository {
        override val settings: Flow<UserSettings> = MutableStateFlow(UserSettings(autoSave = autoSave))
        override suspend fun setThemeMode(mode: dev.buildit.core.model.ThemeMode) = Unit
        override suspend fun setDynamicColor(enabled: Boolean) = Unit
        override suspend fun setLanguage(language: dev.buildit.core.model.AppLanguage) = Unit
        override suspend fun setEditorFontSize(sp: Int) = Unit
        override suspend fun setAutoSave(enabled: Boolean) = Unit
    }

    private fun vm(autoSave: Boolean = true) = CodeViewModel("p", Repo(), Settings(autoSave), object : DispatcherProvider {
        override val io = dispatcher
        override val default = dispatcher
        override val main = dispatcher
    })

    private val file get() = File(project.directory, activity)

    @Test fun editsAutosaveAndReportSyntaxProblems() = runTest(dispatcher) {
        val vm = vm()
        vm.open(activity)
        advanceUntilIdle()
        val original = vm.text(activity)!!
        assertEquals(original, file.readText())
        assertTrue(vm.state.value.currentFile!!.diagnostics.isEmpty())

        val broken = original.replaceFirst("{", "{ int x = (1;")
        vm.onEdited(activity, broken)
        assertTrue(vm.state.value.currentFile!!.dirty)
        advanceUntilIdle()
        assertEquals(broken, file.readText())
        val f = vm.state.value.currentFile!!
        assertFalse(f.dirty)
        assertTrue(f.diagnostics.toString(), f.diagnostics.any { it.code == "mismatched" || it.code == "unclosed" })
    }

    @Test fun externalChangeIsAConflictNotAnOverwrite() = runTest(dispatcher) {
        val vm = vm(autoSave = false)
        vm.open(activity)
        advanceUntilIdle()
        file.writeText("// changed elsewhere\n")
        vm.onEdited(activity, "// my edit\n")
        vm.save(activity)
        advanceUntilIdle()
        assertEquals("// changed elsewhere\n", file.readText())
        assertTrue(vm.state.value.currentFile!!.conflict)
        assertTrue(vm.state.value.currentFile!!.dirty)

        vm.save(activity, force = true)
        advanceUntilIdle()
        assertEquals("// my edit\n", file.readText())
        assertFalse(vm.state.value.currentFile!!.conflict)
        assertFalse(vm.state.value.currentFile!!.dirty)
    }

    @Test fun withoutAutosaveNothingIsWrittenUntilSaved() = runTest(dispatcher) {
        val vm = vm(autoSave = false)
        vm.open(activity)
        advanceUntilIdle()
        val original = file.readText()
        vm.onEdited(activity, "$original\n// note\n")
        vm.flush()
        advanceUntilIdle()
        assertEquals(original, file.readText())

        var left = false
        vm.saveAllThen { left = true }
        advanceUntilIdle()
        assertTrue(left)
        assertEquals("$original\n// note\n", file.readText())
    }

    @Test fun discardRestoresDiskTextAndClosePicksNeighbour() = runTest(dispatcher) {
        val vm = vm(autoSave = false)
        vm.open("app/build.gradle.kts")
        vm.open(activity)
        advanceUntilIdle()
        vm.onEdited(activity, "junk")
        vm.discardAll()
        advanceUntilIdle()
        assertEquals(file.readText(), vm.text(activity))
        assertFalse(vm.state.value.currentFile!!.dirty)
        vm.close(activity)
        assertEquals("app/build.gradle.kts", vm.state.value.current)
    }

    @Test fun onlyTextFilesOpenInTheEditor() {
        assertTrue(CodeViewModel.canOpen("app/src/main/res/layout/activity_main.xml"))
        assertTrue(CodeViewModel.canOpen("app/build.gradle.kts"))
        assertTrue(CodeViewModel.canOpen("app/proguard-rules.pro"))
        assertFalse(CodeViewModel.canOpen("app/src/main/res/drawable/icon.png"))
    }
}
