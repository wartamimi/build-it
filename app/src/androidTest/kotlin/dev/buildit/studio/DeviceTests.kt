package dev.buildit.studio

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buildit.engine.ai.AiConfig
import dev.buildit.engine.ai.AiReply
import dev.buildit.engine.ai.AnthropicAiClient
import dev.buildit.engine.ai.ChatMessage
import dev.buildit.engine.ai.ChatRole
import dev.buildit.engine.ai.OpenAiCompatibleClient
import dev.buildit.engine.ai.ProviderKind
import dev.buildit.engine.ai.StopKind
import dev.buildit.engine.build.BuildPipeline
import dev.buildit.engine.build.BuildRequest
import dev.buildit.engine.build.BuildType
import dev.buildit.engine.build.Keys
import dev.buildit.feature.console.ConsoleEntry
import dev.buildit.feature.console.ConsoleLevel
import dev.buildit.feature.console.ConsoleLog
import dev.buildit.feature.console.SourceLinks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Runs on a device or emulator (the CI emulator job). These check what JVM tests cannot: that the
 * build tools, the AI SDK and the Keystore work on ART, and that an APK built on the device
 * installs, runs and reports to the console.
 */
@RunWith(AndroidJUnit4::class)
class DeviceTests {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as BuildItApplication).container

    private fun shell(cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).bufferedReader().readText()

    private fun install(apk: File): String {
        val (out, input, err) = instrumentation.uiAutomation.executeShellCommandRwe("pm install -t -S ${apk.length()}")
        ParcelFileDescriptor.AutoCloseOutputStream(input).use { o -> apk.inputStream().use { it.copyTo(o) } }
        return ParcelFileDescriptor.AutoCloseInputStream(out).bufferedReader().readText() +
            ParcelFileDescriptor.AutoCloseInputStream(err).bufferedReader().readText()
    }

    private fun waitFor(what: String, timeoutMs: Long = 30_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (check()) return; Thread.sleep(250) }
        throw AssertionError("Timed out waiting for $what")
    }

    private fun entries(pkg: String): List<ConsoleEntry> = ConsoleLog.shared.entries.value.filter { it.packageName == pkg }

    @Test fun javaProjectBuildsOnTheDeviceInstallsRunsAndReportsToTheConsole() = runBlocking {
        val pkg = "com.example.devicecheck"
        val repo = container.projectRepository
        val toolchain = container.toolchain
        val summary = repo.create("Device Check", pkg)
        try {
            val project = repo.open(summary.id)
            val api = project.manifest.app.compileSdk
            if (toolchain.androidJar(api) == null) {
                // The real path: Google's index, the license text, download and SHA-1 check.
                val index = toolchain.fetchIndex()
                val platform = index.platform(api) ?: error("API $api not in Google's index")
                platform.licenseId?.let { id -> toolchain.acceptLicense(id, index.licenses.getValue(id)) }
                toolchain.install(platform)
            }
            val source = "app/src/main/java/com/example/devicecheck/MainActivity.java"
            File(project.directory, source).writeText(
                """
                package com.example.devicecheck;

                import android.app.Activity;
                import android.os.Bundle;
                import android.util.Log;

                public class MainActivity extends Activity {
                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        setContentView(R.layout.activity_main);
                        Log.i("DeviceCheck", "hello from a Build It device build");
                        if (getIntent().getBooleanExtra("crash", false)) {
                            throw new IllegalStateException("crash requested");
                        }
                    }
                }
                """.trimIndent() + "\n",
            )
            val start = System.nanoTime()
            val result = BuildPipeline().run(
                BuildRequest(project.directory, project.manifest.app, toolchain.androidJar(api)!!, BuildType.Debug,
                    key = { Keys.debugKey(container.debugKeystore) }, toolCache = toolchain.cacheDir, console = container.consoleHost),
            )
            Log.i("DeviceTests", "On-device build took ${(System.nanoTime() - start) / 1_000_000} ms")
            assertTrue(result.diagnostics.joinToString("\n"), result.success)

            val out = install(result.apk!!)
            assertTrue(out, out.contains("Success"))

            shell("am start -W -n $pkg/.MainActivity")
            waitFor("the app's log line") { entries(pkg).any { it.tag == "DeviceCheck" && it.text == "hello from a Build It device build" } }
            waitFor("the resumed event") { entries(pkg).any { it.level == ConsoleLevel.Event && it.text == "$pkg.MainActivity resumed" } }

            shell("am force-stop $pkg")
            shell("am start -W --ez crash true -n $pkg/.MainActivity")
            waitFor("the crash") { entries(pkg).any { it.level == ConsoleLevel.Crash && it.text.contains("crash requested") } }
            val crash = entries(pkg).last { it.level == ConsoleLevel.Crash }
            val frame = SourceLinks.frames(crash.text).first { it.fileName == "MainActivity.java" }
            assertEquals(source, SourceLinks.resolve(project.directory, frame))
            assertEquals(14, frame.line)
        } finally {
            shell("pm uninstall $pkg")
            repo.delete(summary.id)
        }
    }

    @Test fun apiKeysAreEncryptedWithTheKeystore() {
        val store = KeystoreSecretStore(instrumentation.targetContext)
        store.write("test-key", "sk-ant-secret-value")
        assertEquals("sk-ant-secret-value", store.read("test-key"))
        val raw = instrumentation.targetContext.getSharedPreferences("secrets", 0).getString("test-key", null)!!
        assertTrue(!raw.contains("secret"))
        store.delete("test-key")
        assertNull(store.read("test-key"))
    }

    /** One-shot HTTP server on the device that answers with [body] and records the request. */
    private class OneShotServer(contentType: String, body: String) : AutoCloseable {
        private val socket = ServerSocket(0)
        @Volatile var request = ""
        val url = "http://127.0.0.1:${socket.localPort}"
        private val worker = thread {
            socket.accept().use { s ->
                val input = s.getInputStream().bufferedReader()
                val head = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                val length = head.firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toInt() ?: 0
                val buf = CharArray(length)
                var read = 0
                while (read < length) { val n = input.read(buf, read, length - read); if (n < 0) break; read += n }
                request = head.joinToString("\n") + "\n\n" + String(buf, 0, read)
                val bytes = body.toByteArray()
                s.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes); flush()
                }
            }
        }
        override fun close() { worker.join(5_000); socket.close() }
    }

    @Test fun claudeSdkStreamsOnArt() {
        val events = listOf(
            "message_start" to """{"type":"message_start","message":{"id":"m","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":3,"output_tokens":1}}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok on ART"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":4}}""",
            "message_stop" to """{"type":"message_stop"}""",
        ).joinToString("") { (e, d) -> "event: $e\ndata: $d\n\n" }
        OneShotServer("text/event-stream", events).use { server ->
            val reply = AnthropicAiClient(AiConfig(baseUrl = server.url), "sk-test").stream("s", listOf(ChatMessage(ChatRole.User, "hi")), {}, { false })
            assertEquals(AiReply("ok on ART", StopKind.End, 3, 4), reply)
            assertTrue(server.request, server.request.contains("claude-opus-5-5"))
        }
    }

    @Test fun localHttpServersAreReachable() {
        val body = "data: {\"choices\":[{\"delta\":{\"content\":\"local\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        OneShotServer("text/event-stream", body).use { server ->
            val reply = OpenAiCompatibleClient(AiConfig(ProviderKind.OpenAiCompatible, "m", server.url + "/v1"), null)
                .stream("s", listOf(ChatMessage(ChatRole.User, "hi")), {}, { false })
            assertEquals("local", reply.text)
        }
    }
}
