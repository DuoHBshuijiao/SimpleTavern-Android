package com.simpletavern.core.importer

import com.simpletavern.core.model.StError
import org.junit.Assert.*
import org.junit.Test

class WorldBookFileDecoderTest {
    @Test fun desktopBookPreservesEntryControlsAndUsesFreshLocalIds() {
        val result = WorldBookFileDecoder.decode("""{"id":"old","name":"知识","globalActive":true,"entries":[{"id":"old-entry","regex":"hero","content":"facts","enabled":false,"scanDepth":3,"position":"at_depth","injectDepth":2}]}""")
        assertEquals("simpletavern", result.sourceFormat)
        val book = result.worldBook
        assertNotEquals("old", book.id)
        assertFalse(book.globalActive)
        assertNull(book.sourceNamespace)
        assertNotEquals("old-entry", book.entries.single().id)
        assertEquals(3, book.entries.single().scanDepth)
        assertEquals("at_depth", book.entries.single().position)
        assertFalse(book.entries.single().enabled)
    }

    @Test fun sillyTavernDictionarySupportsKeysConstantsAndDisabledEntries() {
        val raw = """{"entries":{"0":{"key":["C++"],"content":"facts","order":10,"position":4,"depth":2},"1":{"constant":true,"disable":true,"content":"hidden"}}}"""
        val result = WorldBookFileDecoder.decode(raw, "Lore.json")
        assertEquals("sillytavern", result.sourceFormat)
        assertEquals("Lore", result.worldBook.name)
        val entry = result.worldBook.entries.first()
        assertTrue(Regex(entry.regex).containsMatchIn("C++"))
        assertFalse(Regex(entry.regex).containsMatchIn("CCC"))
        assertEquals("at_depth", entry.position)
        assertTrue(result.worldBook.entries.last().constantActive)
        assertFalse(result.worldBook.entries.last().enabled)
    }

    @Test fun selectiveEntriesRequirePrimaryAndSecondaryKeys() {
        val entry = WorldBookFileDecoder.decode("""{"entries":[{"keys":["dragon"],"keysecondary":["cave"],"selective":true,"content":"facts"}]}""").worldBook.entries.single()
        assertTrue(Regex(entry.regex).containsMatchIn("A DRAGON enters the cave"))
        assertFalse(Regex(entry.regex).containsMatchIn("dragon flies"))
    }

    @Test fun bomAndEmptyBooksAreSupported() {
        assertTrue(WorldBookFileDecoder.decode("\uFEFF{\"entries\":[]}").worldBook.entries.isEmpty())
    }

    @Test(expected = StError.Validation::class) fun unrelatedJsonIsRejected() { WorldBookFileDecoder.decode("""{"messages":[]}""") }
    @Test(expected = StError.Validation::class) fun duplicateKeysAreRejected() { WorldBookFileDecoder.decode("""{"entries":[],"entries":[]}""") }
    @Test(expected = StError.Validation::class) fun invalidRegularExpressionIsRejected() {
        WorldBookFileDecoder.decode("""{"entries":[{"regex":"["}]}""")
    }
    @Test(expected = StError.Validation::class) fun oversizedInputIsRejectedBeforeParsing() {
        WorldBookFileDecoder.decode(" ".repeat(WorldBookFileDecoder.MAX_INPUT_BYTES + 1))
    }
}
