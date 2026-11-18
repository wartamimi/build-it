package dev.buildit.engine.ai

enum class ChatRole { User, Assistant }

data class ChatMessage(val role: ChatRole, val text: String)

enum class StopKind { End, MaxTokens, Refusal, Other }

data class AiReply(val text: String, val stop: StopKind, val inputTokens: Long?, val outputTokens: Long?)

/** A failed request, classified so the UI can say what to do about it. The message never holds the key. */
class AiException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { Auth, RateLimit, Overloaded, BadRequest, NotFound, Network, Server, Cancelled }
}

/**
 * A chat model. [stream] blocks the calling thread, calls [onText] with each piece of text as it
 * arrives, stops when [isCancelled] turns true (throwing [AiException.Kind.Cancelled]), and returns
 * the whole reply.
 */
interface AiClient {
    fun stream(system: String, messages: List<ChatMessage>, onText: (String) -> Unit, isCancelled: () -> Boolean): AiReply
}

object AiClients {
    fun create(config: AiConfig, apiKey: String?): AiClient = when (config.provider) {
        ProviderKind.Anthropic -> AnthropicAiClient(config, apiKey ?: throw AiException(AiException.Kind.Auth, "No API key"))
        ProviderKind.OpenAiCompatible -> OpenAiCompatibleClient(config, apiKey)
    }
}
