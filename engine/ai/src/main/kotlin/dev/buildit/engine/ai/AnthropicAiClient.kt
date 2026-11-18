package dev.buildit.engine.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import java.time.Duration

/** Claude through Anthropic's official Java SDK, streamed so long file proposals don't time out. */
class AnthropicAiClient(private val config: AiConfig, apiKey: String) : AiClient {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (config.baseUrl.isNotBlank()) baseUrl(config.baseUrl.trimEnd('/')) }
        .timeout(Duration.ofMinutes(10))
        .maxRetries(2)
        .build()

    override fun stream(system: String, messages: List<ChatMessage>, onText: (String) -> Unit, isCancelled: () -> Boolean): AiReply {
        val params = MessageCreateParams.builder()
            .model(config.model)
            .maxTokens(config.maxTokens.toLong())
            .system(system)
            .apply {
                messages.forEach { m -> if (m.role == ChatRole.User) addUserMessage(m.text) else addAssistantMessage(m.text) }
            }
            .build()
        val text = StringBuilder()
        var stop: StopReason? = null
        var input: Long? = null
        var output: Long? = null
        try {
            client.messages().createStreaming(params).use { response ->
                val events = response.stream().iterator()
                while (events.hasNext()) {
                    if (isCancelled()) throw AiException(AiException.Kind.Cancelled, "Cancelled")
                    val e = events.next()
                    e.messageStart().ifPresent { s -> input = s.message().usage().inputTokens() }
                    e.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { t ->
                        text.append(t.text()); onText(t.text())
                    }
                    e.messageDelta().ifPresent { d ->
                        d.delta().stopReason().ifPresent { stop = it }
                        output = d.usage().outputTokens()
                    }
                }
            }
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            if (isCancelled()) throw AiException(AiException.Kind.Cancelled, "Cancelled", e)
            throw classify(e)
        }
        val kind = when (stop) {
            StopReason.END_TURN, StopReason.STOP_SEQUENCE -> StopKind.End
            StopReason.MAX_TOKENS, StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED -> StopKind.MaxTokens
            StopReason.REFUSAL -> StopKind.Refusal
            else -> StopKind.Other
        }
        return AiReply(text.toString(), kind, input, output)
    }

    private fun classify(e: Exception): AiException {
        val k = when (e) {
            is UnauthorizedException, is PermissionDeniedException -> AiException.Kind.Auth
            is RateLimitException -> AiException.Kind.RateLimit
            is NotFoundException -> AiException.Kind.NotFound
            is BadRequestException -> AiException.Kind.BadRequest
            is InternalServerException -> if (e.statusCode() == 529) AiException.Kind.Overloaded else AiException.Kind.Server
            is AnthropicServiceException -> if (e.statusCode() == 529) AiException.Kind.Overloaded else AiException.Kind.Server
            is AnthropicIoException -> AiException.Kind.Network
            is java.io.IOException -> AiException.Kind.Network
            else -> AiException.Kind.Server
        }
        return AiException(k, e.message ?: e.javaClass.simpleName, e)
    }
}
