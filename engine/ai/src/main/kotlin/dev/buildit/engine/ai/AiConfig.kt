package dev.buildit.engine.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class ProviderKind {
    /** Claude, through Anthropic's official Java SDK. */
    Anthropic,

    /** Any server that speaks the OpenAI chat-completions protocol (local models, other vendors). */
    OpenAiCompatible,
}

/** Provider settings. The API key is not part of it: it lives only in a [SecretStore]. */
@Serializable
data class AiConfig(
    val provider: ProviderKind = ProviderKind.Anthropic,
    val model: String = DEFAULT_ANTHROPIC_MODEL,
    /** Empty means the provider's official endpoint (required for [ProviderKind.OpenAiCompatible]). */
    val baseUrl: String = "",
    val maxTokens: Int = 16_000,
) {
    /** What is missing before the assistant can be used, or null when the setup is complete. */
    fun problem(hasKey: Boolean): SetupProblem? = when {
        model.isBlank() -> SetupProblem.NoModel
        provider == ProviderKind.OpenAiCompatible && !baseUrl.startsWith("https://") && !isLocalHttp(baseUrl) -> SetupProblem.BadUrl
        provider == ProviderKind.Anthropic && baseUrl.isNotEmpty() && !baseUrl.startsWith("https://") -> SetupProblem.BadUrl
        // Local OpenAI-compatible servers (Ollama, llama.cpp) usually run without a key.
        provider == ProviderKind.Anthropic && !hasKey -> SetupProblem.NoKey
        maxTokens !in 256..128_000 -> SetupProblem.BadMaxTokens
        else -> null
    }

    companion object {
        const val DEFAULT_ANTHROPIC_MODEL = "claude-opus-5-5"

        /** Plain HTTP is only accepted for servers on the device or the local network. */
        fun isLocalHttp(url: String): Boolean {
            val host = url.removePrefix("http://").takeIf { it != url }?.substringBefore('/')?.substringBefore(':') ?: return false
            return host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" ||
                host.startsWith("192.168.") || host.startsWith("10.") || host.endsWith(".local")
        }
    }
}

enum class SetupProblem { NoModel, NoKey, BadUrl, BadMaxTokens }

/** Encrypted storage for secrets. The app implements it with the Android Keystore. */
interface SecretStore {
    fun read(name: String): String?
    fun write(name: String, value: String)
    fun delete(name: String)
}

/** Keeps [AiConfig] in a JSON file and the API key in the [SecretStore]. */
class AiSettingsStore(private val file: File, private val secrets: SecretStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun load(): AiConfig = runCatching { json.decodeFromString(AiConfig.serializer(), file.readText()) }.getOrElse { AiConfig() }

    fun save(config: AiConfig) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(AiConfig.serializer(), config))
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    }

    fun apiKey(provider: ProviderKind): String? = secrets.read(keyName(provider))?.takeIf { it.isNotBlank() }

    fun setApiKey(provider: ProviderKind, key: String?) {
        if (key.isNullOrBlank()) secrets.delete(keyName(provider)) else secrets.write(keyName(provider), key.trim())
    }

    private fun keyName(p: ProviderKind) = "ai-key-" + p.name.lowercase()
}
