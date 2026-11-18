package dev.buildit.engine.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AiSettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private class MemorySecrets : SecretStore {
        val map = HashMap<String, String>()
        override fun read(name: String) = map[name]
        override fun write(name: String, value: String) { map[name] = value }
        override fun delete(name: String) { map.remove(name) }
    }

    @Test fun `config is saved without the key, keys are per provider`() {
        val file = File(tmp.root, "ai/config.json")
        val secrets = MemorySecrets()
        val store = AiSettingsStore(file, secrets)
        assertEquals(AiConfig(), store.load())
        val cfg = AiConfig(ProviderKind.OpenAiCompatible, "llama", "http://192.168.1.5:11434/v1", 4000)
        store.save(cfg)
        store.setApiKey(ProviderKind.Anthropic, "  sk-ant-secret ")
        assertEquals(cfg, AiSettingsStore(file, secrets).load())
        assertFalse(file.readText().contains("sk-ant"))
        assertEquals("sk-ant-secret", store.apiKey(ProviderKind.Anthropic))
        assertNull(store.apiKey(ProviderKind.OpenAiCompatible))
        store.setApiKey(ProviderKind.Anthropic, "")
        assertNull(store.apiKey(ProviderKind.Anthropic))
    }

    @Test fun `setup problems`() {
        assertEquals(SetupProblem.NoKey, AiConfig().problem(hasKey = false))
        assertNull(AiConfig().problem(hasKey = true))
        assertEquals(SetupProblem.BadUrl, AiConfig(ProviderKind.OpenAiCompatible, "m", "http://example.com/v1").problem(false))
        assertNull(AiConfig(ProviderKind.OpenAiCompatible, "m", "http://localhost:11434/v1").problem(false))
        assertEquals(SetupProblem.NoModel, AiConfig(model = " ").problem(true))
    }
}
