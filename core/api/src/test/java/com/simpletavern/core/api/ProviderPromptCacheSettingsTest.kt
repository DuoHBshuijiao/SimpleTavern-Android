package com.simpletavern.core.api

import com.simpletavern.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProviderPromptCacheSettingsTest {
    private val chat = ChatSession("chat", "character", createdAt = "2026-10-29T00:00:00Z", updatedAt = "2026-10-29T00:00:00Z")
    private fun select(protocol: LlmProtocol, model: String, url: String = "https://api.openai.com/v1", mode: String = "auto") =
        ProviderPromptCacheSettings.select(buildJsonObject {
            put("promptCache", buildJsonObject { put("mode", mode); put("ttl", "30m") })
        }, protocol, url, model, "openai", chat, null).params

    @Test fun legacyModelsAndChatDoNotReceiveResponsesCacheOptions() {
        for (mode in listOf("auto", "explicit")) {
            val legacy = select(LlmProtocol.OPENAI_RESPONSES, "gpt-4.1", mode = mode)
            assertNull(legacy["prompt_cache_options"])
            assertNull(legacy["cache_breakpoints"])
            val chat = select(LlmProtocol.OPENAI_COMPATIBLE, "gpt-5.6", mode = mode)
            assertNull(chat["prompt_cache_options"])
            assertNull(chat["cache_breakpoints"])
        }
    }

    @Test fun compatibleEndpointsDegradeToTransparentCaching() {
        for (url in listOf("https://api.x.ai/v1", "https://generativelanguage.googleapis.com/v1beta/openai", "https://proxy.example/v1")) {
            for (mode in listOf("auto", "explicit")) {
                val params = select(LlmProtocol.OPENAI_COMPATIBLE, "model", url, mode)
                assertTrue(params.isEmpty())
            }
        }
    }

    @Test fun modelsPredatingPromptCachingGetNoCacheParameters() {
        assertTrue(select(LlmProtocol.OPENAI_COMPATIBLE, "gpt-3.5-turbo").isEmpty())
    }

    @Test fun newerResponsesStillSupportExplicitSystemCache() {
        val params = select(LlmProtocol.OPENAI_RESPONSES, "gpt-5.6", mode = "explicit")
        assertEquals(JsonPrimitive("explicit"), params["prompt_cache_options"]!!.jsonObject["mode"])
        assertNotNull(params["cache_breakpoints"])
    }

    @Test fun unsupportedResponsesBreakpointAndNumericTtlDegradeWithoutFailure() {
        val params = ProviderPromptCacheSettings.select(buildJsonObject {
            put("promptCache", buildJsonObject {
                put("mode", "explicit"); put("ttl", 3600)
                put("breakpoints", buildJsonArray { add("history_tail") })
            })
        }, LlmProtocol.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-5.6", "openai", chat, null).params
        assertEquals(JsonPrimitive("implicit"), params["prompt_cache_options"]!!.jsonObject["mode"])
        assertNull(params["cache_breakpoints"])
    }
}
