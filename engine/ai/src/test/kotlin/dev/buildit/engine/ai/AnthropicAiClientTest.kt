package dev.buildit.engine.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnthropicAiClientTest {
    private fun sse(vararg events: Pair<String, String>) = events.joinToString("") { (e, d) -> "event: $e\ndata: $d\n\n" }

    private val stream = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":42,"output_tokens":1}}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello, "}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"world"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":7}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    @Test fun `streams text through the official SDK and sends the conversation`() {
        FakeServer(200, "text/event-stream", stream).use { s ->
            val client = AnthropicAiClient(AiConfig(baseUrl = s.url, maxTokens = 2048), "sk-test")
            val pieces = mutableListOf<String>()
            val reply = client.stream(
                "system rules",
                listOf(ChatMessage(ChatRole.User, "hi"), ChatMessage(ChatRole.Assistant, "hello"), ChatMessage(ChatRole.User, "fix it")),
                { pieces += it }, { false },
            )
            assertEquals(listOf("Hello, ", "world"), pieces)
            assertEquals(AiReply("Hello, world", StopKind.End, 42, 7), reply)
            val r = s.requests.single()
            assertEquals("/v1/messages", r.path)
            assertEquals("Bearer sk-test", r.headers["authorization"] ?: r.headers["x-api-key"]?.let { "Bearer $it" })
            assertTrue(r.body, r.body.contains("\"model\":\"claude-opus-5-5\""))
            assertTrue(r.body.contains("\"max_tokens\":2048"))
            assertTrue(r.body.contains("\"stream\":true"))
            assertTrue(r.body.contains("system rules"))
            assertTrue(r.body.indexOf("\"hi\"") < r.body.indexOf("\"hello\"") && r.body.indexOf("\"hello\"") < r.body.indexOf("\"fix it\""))
        }
    }

    @Test fun `a rejected key is reported as an auth error`() {
        FakeServer(401, "application/json", """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""").use { s ->
            try {
                AnthropicAiClient(AiConfig(baseUrl = s.url), "bad").stream("s", listOf(ChatMessage(ChatRole.User, "x")), {}, { false })
                fail()
            } catch (e: AiException) {
                assertEquals(AiException.Kind.Auth, e.kind)
                assertTrue(e.message!!.contains("invalid x-api-key"))
            }
        }
    }

    @Test fun `cancelling stops the stream`() {
        FakeServer(200, "text/event-stream", stream).use { s ->
            try {
                AnthropicAiClient(AiConfig(baseUrl = s.url), "k").stream("s", listOf(ChatMessage(ChatRole.User, "x")), {}, { true })
                fail()
            } catch (e: AiException) {
                assertEquals(AiException.Kind.Cancelled, e.kind)
            }
        }
    }
}
