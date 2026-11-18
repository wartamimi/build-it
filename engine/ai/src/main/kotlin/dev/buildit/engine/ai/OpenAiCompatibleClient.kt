package dev.buildit.engine.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * A server that speaks the OpenAI chat-completions protocol (`POST {baseUrl}/chat/completions`
 * with `stream: true`). Used for providers the user picks other than Claude, such as a local model.
 */
class OpenAiCompatibleClient(private val config: AiConfig, private val apiKey: String?) : AiClient {
    private val json = Json { ignoreUnknownKeys = true }

    override fun stream(system: String, messages: List<ChatMessage>, onText: (String) -> Unit, isCancelled: () -> Boolean): AiReply {
        val body = buildJsonObject {
            put("model", config.model)
            put("max_tokens", config.maxTokens)
            put("stream", true)
            putJsonObject("stream_options") { put("include_usage", true) }
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                messages.forEach { m -> addJsonObject { put("role", if (m.role == ChatRole.User) "user" else "assistant"); put("content", m.text) } }
            }
        }
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val c = try { URI(url).toURL().openConnection() as HttpURLConnection } catch (e: Exception) {
            throw AiException(AiException.Kind.BadRequest, "Invalid endpoint URL", e)
        }
        val text = StringBuilder()
        var finish: String? = null
        var input: Long? = null
        var output: Long? = null
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20_000
            c.readTimeout = 120_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "text/event-stream")
            apiKey?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) {
                val detail = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                throw AiException(kindFor(code), "HTTP $code${errorMessage(detail)?.let { ": $it" } ?: ""}")
            }
            c.inputStream.bufferedReader().use { r ->
                while (true) {
                    if (isCancelled()) throw AiException(AiException.Kind.Cancelled, "Cancelled")
                    val line = r.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    obj["error"]?.let { throw AiException(AiException.Kind.Server, errorMessage(data) ?: "Provider error") }
                    (obj["usage"] as? JsonObject)?.let { u ->
                        input = (u["prompt_tokens"] as? JsonPrimitive)?.longOrNull ?: input
                        output = (u["completion_tokens"] as? JsonPrimitive)?.longOrNull ?: output
                    }
                    val choice = (obj["choices"]?.jsonArray?.firstOrNull() as? JsonObject) ?: continue
                    (choice["delta"] as? JsonObject)?.get("content")?.let { it as? JsonPrimitive }?.contentOrNull?.let { t ->
                        text.append(t); onText(t)
                    }
                    (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.let { finish = it }
                }
            }
        } catch (e: AiException) {
            throw e
        } catch (e: IOException) {
            if (isCancelled()) throw AiException(AiException.Kind.Cancelled, "Cancelled", e)
            throw AiException(AiException.Kind.Network, e.message ?: e.javaClass.simpleName, e)
        } finally {
            c.disconnect()
        }
        val stop = when (finish) {
            "stop" -> StopKind.End
            "length" -> StopKind.MaxTokens
            "content_filter" -> StopKind.Refusal
            else -> StopKind.Other
        }
        return AiReply(text.toString(), stop, input, output)
    }

    private fun kindFor(code: Int) = when (code) {
        401, 403 -> AiException.Kind.Auth
        404 -> AiException.Kind.NotFound
        429 -> AiException.Kind.RateLimit
        in 400..499 -> AiException.Kind.BadRequest
        503, 529 -> AiException.Kind.Overloaded
        else -> AiException.Kind.Server
    }

    private fun errorMessage(body: String): String? = runCatching {
        val e = json.parseToJsonElement(body).jsonObject["error"]
        (e as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: (e as? JsonPrimitive)?.contentOrNull
    }.getOrNull()?.take(300)
}
