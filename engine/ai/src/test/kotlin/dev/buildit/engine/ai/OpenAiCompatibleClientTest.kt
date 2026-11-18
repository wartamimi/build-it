package dev.buildit.engine.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenAiCompatibleClientTest {
    @Test fun `streams chat completions`() {
        val body = """
            |data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"}}]}
            |
            |data: {"choices":[{"index":0,"delta":{"content":" there"},"finish_reason":null}]}
            |
            |data: {"choices":[{"index":0,"delta":{},"finish_reason":"length"}]}
            |
            |data: {"choices":[],"usage":{"prompt_tokens":9,"completion_tokens":3}}
            |
            |data: [DONE]
            |
            |""".trimMargin()
        FakeServer(200, "text/event-stream", body).use { s ->
            val client = OpenAiCompatibleClient(AiConfig(ProviderKind.OpenAiCompatible, "qwen-coder", s.url + "/v1/", 1000), "key1")
            val reply = client.stream("sys", listOf(ChatMessage(ChatRole.User, "q")), {}, { false })
            assertEquals(AiReply("Hi there", StopKind.MaxTokens, 9, 3), reply)
            val r = s.requests.single()
            assertEquals("/v1/chat/completions", r.path)
            assertEquals("Bearer key1", r.headers["authorization"])
            assertTrue(r.body.contains("\"role\":\"system\",\"content\":\"sys\""))
            assertTrue(r.body.contains("\"model\":\"qwen-coder\""))
        }
    }

    @Test fun `no key sends no authorization header, and errors carry the provider message`() {
        FakeServer(404, "application/json", """{"error":{"message":"model 'x' not found"}}""").use { s ->
            try {
                OpenAiCompatibleClient(AiConfig(ProviderKind.OpenAiCompatible, "x", s.url), null).stream("s", emptyList(), {}, { false })
                fail()
            } catch (e: AiException) {
                assertEquals(AiException.Kind.NotFound, e.kind)
                assertEquals("HTTP 404: model 'x' not found", e.message)
            }
            assertEquals(null, s.requests.single().headers["authorization"])
        }
    }
}
