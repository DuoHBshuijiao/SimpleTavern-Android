package com.simpletavern.core.api

import com.simpletavern.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppSettingsCodecTest {
    @Test fun missingSettingsHaveTypedDefaults() {
        val value = AppSettingsCodec.decode(null)
        assertTrue(value.streamEnabled)
        assertFalse(value.pureAiMode)
        assertNull(value.generationDefaults.contextSize)
        assertEquals(2, value.worldBookEntryScanDepthDefault)
    }

    @Test fun roundTripKeepsProviderAndFutureSettingsAtEveryLevel() {
        val raw = """{"themeId":"dark","future":null,"llm":{"protocol":"gemini_generate_content","providerParams":{"region":"test"}},"generationDefaults":{"maxTokens":512,"contextSize":0,"topP":0.5,"reasoning_effort":"high"},"prompts":{"globalSystem":"角色","custom":"future"}}"""
        val value = AppSettingsCodec.decode(raw)
        assertEquals(512, value.generationDefaults.maxTokens)
        assertEquals(0, value.generationDefaults.contextSize)
        assertEquals("角色", value.prompts.globalSystem)
        val saved = AppSettingsCodec.decode(AppSettingsCodec.encode(value.copy(themeId = "light"), raw))
        assertEquals(value.copy(themeId = "light"), saved)
        val stored = Json.parseToJsonElement(AppSettingsCodec.encode(saved, raw)).jsonObject
        assertNull(stored["generationDefaults"]!!.jsonObject["maxTokens"])
        assertEquals(JsonPrimitive(512), stored["generationDefaults"]!!.jsonObject["max_tokens"])
    }

    @Test fun fieldsAddedAfterLoadingArePreservedWhenSavingTypedChanges() {
        val loaded = AppSettingsCodec.decode("{}")
        val current = """{"future":true,"generationDefaults":{"future":3},"prompts":{"future":"keep"}}"""
        val saved = AppSettingsCodec.decode(AppSettingsCodec.encode(loaded.copy(pureAiMode = true), current))
        assertEquals(JsonPrimitive(true), saved.extensions["future"])
        assertEquals(JsonPrimitive(3), saved.generationDefaults.extensions["future"])
        assertEquals(JsonPrimitive("keep"), saved.prompts.extensions["future"])
    }

    @Test fun legacyTopLevelConnectionIsNotShadowedByAnEmptyLlmObject() {
        val raw = """{"model":"custom","baseUrl":"https://gateway.example/v1","protocol":"openai_compatible_chat"}"""
        val stored = Json.parseToJsonElement(AppSettingsCodec.encode(AppSettingsCodec.decode(raw), raw)).jsonObject
        assertEquals(JsonPrimitive("custom"), stored["model"])
        assertEquals(JsonNull, stored["llm"])
    }

    @Test(expected = StError.Validation::class) fun negativeContextBudgetIsRejected() {
        AppSettingsCodec.encode(AppSettings(generationDefaults = GenerationDefaults(contextSize = -1)))
    }
    @Test(expected = StError.Validation::class) fun malformedKnownSettingIsRejected() {
        AppSettingsCodec.decode("""{"streamEnabled":"yes"}""")
    }
}
