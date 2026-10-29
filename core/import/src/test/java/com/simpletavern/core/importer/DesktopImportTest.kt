package com.simpletavern.core.importer

import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.model.StError
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class DesktopImportTest {
    @Test fun desktopGraphWithoutChatIdDecodesWithMappedOwner() {
        val graph = decodeDesktopKnowledgeGraph("""{"version":3,"entities":[{"id":"e1","name":"角色","type":"character"}],"relations":[{"subject":"e1","predicate":"knows","object":"e2"}]}""", "mapped-chat")
        assertEquals("mapped-chat", graph.chatId)
        assertEquals(3, graph.version)
        assertEquals("角色", graph.entities.single().name)
        assertEquals("e2", graph.relations.single().`object`)
    }

    @Test fun sidecarCannotOverrideItsMappedChat() {
        assertEquals("mapped-chat", decodeDesktopKnowledgeGraph("""{"chatId":"unrelated-chat"}""", "mapped-chat").chatId)
    }

    @Test fun emptyDesktopGraphIsSupported() {
        assertTrue(decodeDesktopKnowledgeGraph("{}", "chat").entities.isEmpty())
    }

    @Test(expected = StError.Validation::class) fun nonObjectGraphIsRejected() {
        decodeDesktopKnowledgeGraph("[]", "chat")
    }

    @Test fun usageLedgerPreservesEventIdentityAndTokens() {
        val raw = Jsonx.json.parseToJsonElement("""{"version":1,"eventId":"event-1","ts":"2026-10-29T00:00:00Z","chatId":"source-chat","usage":{"inputTokens":12,"outputTokens":7}}""").jsonObject
        val row = DesktopUsageMapper.map(raw)
        assertEquals("event-1", row.eventId)
        assertEquals("source-chat", row.chatId)
        assertEquals(12L, row.tokens.inputTokens)
        assertEquals(7L, row.tokens.outputTokens)
    }
}
