package dev.buildit.feature.ai

import dev.buildit.core.common.DispatcherProvider
import dev.buildit.core.data.OpenProject
import dev.buildit.core.data.ProjectRepository
import dev.buildit.core.model.ProjectSummary
import dev.buildit.engine.ai.AiClient
import dev.buildit.engine.ai.AiConfig
import dev.buildit.engine.ai.AiException
import dev.buildit.engine.ai.AiReply
import dev.buildit.engine.ai.AiSettingsStore
import dev.buildit.engine.ai.ChatMessage
import dev.buildit.engine.ai.ChatRole
import dev.buildit.engine.ai.SecretStore
import dev.buildit.engine.ai.StopKind
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class AiViewModelTest {
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

    private class Secrets : SecretStore {
        val map = HashMap<String, String>()
        override fun read(name: String) = map[name]
        override fun write(name: String, value: String) { map[name] = value }
        override fun delete(name: String) { map.remove(name) }
    }

    private class Recording(private val reply: String) : AiClient {
        val calls = mutableListOf<Pair<String, List<ChatMessage>>>()
        override fun stream(system: String, messages: List<ChatMessage>, onText: (String) -> Unit, isCancelled: () -> Boolean): AiReply {
            calls += system to messages
            onText(reply)
            return AiReply(reply, StopKind.End, 10, 20)
        }
    }

    private val mainJava = "app/src/main/java/com/example/demo/MainActivity.java"

    @Test fun contextIsSentAndChangesAreAppliedOnlyWhenAccepted() = runTest(dispatcher) {
        val secrets = Secrets()
        val store = AiSettingsStore(File(tmp.root, "ai.json"), secrets).apply { setApiKey(AiConfig().provider, "sk-1") }
        val newText = "package com.example.demo;\n\n// changed by AI\n"
        val client = Recording("Here you go.\n<<<FILE path=\"$mainJava\">>>\n$newText<<<END>>>\n<<<FILE path=\"../outside.txt\">>>\nx\n<<<END>>>")
        val written = mutableListOf<String>()
        val vm = AiViewModel("p", Repo(), store, dispatchers, { listOf("$mainJava:3: error: oops") }, { written += it }, { _, key -> assertEquals("sk-1", key); client })
        advanceUntilIdle()
        assertFalse(vm.state.value.editingSetup)
        assertTrue(mainJava in vm.state.value.projectFiles)
        assertFalse(vm.state.value.projectFiles.any { it == "buildit.json" || it.startsWith(".buildit") })

        val original = File(project.directory, mainJava).readText()
        vm.toggleContext(mainJava)
        vm.onInput("Add a comment")
        vm.send()
        advanceUntilIdle()

        val (system, messages) = client.calls.single()
        assertTrue(system.contains("com.example.demo"))
        val turn = messages.single()
        assertEquals(ChatRole.User, turn.role)
        assertTrue(turn.text.contains("<file path=\"$mainJava\">\n$original"))
        assertTrue(turn.text.contains("oops"))

        val reply = vm.state.value.items.last() as ChatItem.Assistant
        assertEquals(listOf(mainJava, "../outside.txt"), reply.changes.map { it.change.path })
        assertEquals(original, File(project.directory, mainJava).readText()) // nothing written yet
        assertTrue(written.isEmpty())

        vm.applyAll(reply.id)
        advanceUntilIdle()
        val after = vm.state.value.items.last() as ChatItem.Assistant
        assertEquals(listOf(ChangeStatus.Applied, ChangeStatus.Pending), after.changes.map { it.status })
        assertEquals(newText, File(project.directory, mainJava).readText())
        assertEquals(listOf(mainJava), written)
        assertFalse(File(project.directory.parentFile, "outside.txt").exists())
    }

    @Test fun missingKeyOpensSetupAndErrorsAreShown() = runTest(dispatcher) {
        val store = AiSettingsStore(File(tmp.root, "ai.json"), Secrets())
        val failing = object : AiClient {
            override fun stream(system: String, messages: List<ChatMessage>, onText: (String) -> Unit, isCancelled: () -> Boolean): AiReply =
                throw AiException(AiException.Kind.Auth, "invalid x-api-key")
        }
        val vm = AiViewModel("p", Repo(), store, dispatchers, { emptyList() }, {}, { _, _ -> failing })
        advanceUntilIdle()
        assertTrue(vm.state.value.editingSetup)
        vm.onInput("hi"); vm.send(); advanceUntilIdle()
        assertTrue(vm.state.value.items.isEmpty()) // nothing is sent before setup is complete

        vm.saveSetup(AiConfig(), "sk-x")
        advanceUntilIdle()
        assertFalse(vm.state.value.editingSetup)
        vm.send(); advanceUntilIdle()
        val f = vm.state.value.items.last() as ChatItem.Failure
        assertEquals(AiException.Kind.Auth, f.kind)
        assertFalse(vm.state.value.running)
    }
}
