package com.simpletavern.core.memory

import com.simpletavern.core.llm.ProviderConfig
import com.simpletavern.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ContextTokensTest {
    @Test fun shortTextNeverDisappearsAndChineseIsNotDividedByFour() {
        for (text in listOf("a", "ab", "abc", "你", "你好", "🙂")) assertTrue(ContextTokens.text(text) > 0)
        assertTrue(ContextTokens.text("中文上下文") >= 6)
        assertEquals(0, ContextTokens.text(""))
    }

    @Test fun mixedScriptAndSurrogatePairsAreCounted() {
        assertTrue(ContextTokens.text("hello 世界🙂") > ContextTokens.text("hello"))
    }

    @Test fun imageAndToolDefinitionsConsumeBudget() {
        val plain = ContextMessage(ChatRole.user, "看图")
        val image = AttachmentRef("image", "photo.png", "image/png", "images/photo.png", width = 1024, height = 1024)
        assertTrue(ContextTokens.message(plain.copy(attachments = listOf(image))) >= ContextTokens.message(plain) + 4096)
        assertTrue(ContextTokens.tools("""[{"type":"function","function":{"name":"search"}}]""") > 0)
        assertEquals(0, ContextTokens.tools("[]"))
    }

    @Test fun outputReservationRecognizesProtocolSpecificLimits() {
        val provider = ProviderConfig("test", LlmProtocol.OPENAI_RESPONSES, "https://api.openai.com", "", "model",
            extra = mapOf("max_tokens" to "500", "max_output_tokens" to "2000"))
        assertEquals(2000, ContextTokens.output(provider))
    }

    @Test fun anchorRetainsNMessagesBeforeAnchor() {
        for (count in listOf(null, 0, -3)) assertEquals(0, anchorKeepBefore(count))
        assertEquals(1, anchorKeepBefore(1))
        assertEquals(5, anchorKeepBefore(5))
    }

    @Test(expected = StError.Validation::class) fun outputAndToolsReduceAvailableInputBudget() {
        ContextTokens.requireFits(100, listOf(ContextMessage(ChatRole.user, "hi")), 90, "schema".repeat(20))
    }

    @Test fun disabledBudgetKeepsAllHistoryRegardlessOfEstimatedCost() {
        ContextTokens.requireFits(0, listOf(ContextMessage(ChatRole.user, "历史".repeat(50_000))), 100_000, "schema")
    }
}
