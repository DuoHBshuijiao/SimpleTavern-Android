package com.simpletavern.core.api

import android.app.Application
import androidx.room.Room
import com.simpletavern.core.data.*
import com.simpletavern.core.data.db.AppDatabase
import com.simpletavern.core.model.*
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class FrontendRoomApiTest {
    private lateinit var db: AppDatabase
    private lateinit var content: ContentRepository
    private lateinit var chats: ChatRepository
    private val executor = Executors.newCachedThreadPool()
    private val at = "2026-10-29T00:00:00Z"
    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .setQueryExecutor(executor).setTransactionExecutor(executor).allowMainThreadQueries().build()
        content = ContentRepository(db)
        chats = ChatRepository(db)
    }
    @After fun close() { db.close(); executor.shutdownNow() }

    private suspend fun <T> observed(flow: Flow<T>, block: suspend (Channel<T>) -> Unit) = coroutineScope {
        val values = Channel<T>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) { flow.collect { values.send(it) } }
        try { withTimeout(15_000) { block(values) } } finally { job.cancelAndJoin(); values.cancel() }
    }
    private suspend fun <T> Channel<T>.nextWhere(predicate: (T) -> Boolean): T = withTimeout(10_000) {
        var value = receive()
        while (!predicate(value)) value = receive()
        value
    }
    private fun character(id: String, name: String = id) = CharacterCard(id, name, createdAt = at, updatedAt = at)
    private fun chat(id: String) = ChatSession(id, "hero", createdAt = at, updatedAt = at)
    private fun message(id: String, text: String) = MessageCandidate(id, ChatRole.assistant, text, createdAt = at)

    @Test fun charactersObserveCommittedInsertRenameAndDeleteInDisplayOrder() = runBlocking {
        observed(content.observeCharacters()) { values ->
            assertTrue(values.receive().isEmpty())
            content.saveCharacter(character("z", "Zed"))
            assertEquals("z", values.nextWhere { it.size == 1 }.single().id)
            content.saveCharacter(character("a", "Ada"))
            assertEquals(listOf("a", "z"), values.nextWhere { it.size == 2 }.map { it.id })
            content.saveCharacter(character("z", "Aaron"))
            assertEquals(listOf("z", "a"), values.nextWhere { it.firstOrNull()?.name == "Aaron" }.map { it.id })
            db.characters().delete("z")
            assertEquals("a", values.nextWhere { it.size == 1 }.single().id)
        }
    }

    @Test fun personasPresetsAndWorldBooksObserveStorageChanges() = runBlocking {
        observed(content.observePersonas()) { values ->
            assertTrue(values.receive().isEmpty())
            content.savePersona(UserPersona("p", "Persona", createdAt = at, updatedAt = at))
            assertEquals("p", values.nextWhere { it.isNotEmpty() }.single().id)
            db.personas().delete("p")
            assertTrue(values.nextWhere { it.isEmpty() }.isEmpty())
        }
        observed(content.observePresets()) { values ->
            assertTrue(values.receive().isEmpty())
            content.savePreset(ModelPreset("preset", "Model", "{}", at, at))
            assertEquals("preset", values.nextWhere { it.isNotEmpty() }.single().id)
            db.config().deletePreset("preset")
            assertTrue(values.nextWhere { it.isEmpty() }.isEmpty())
        }
        observed(content.observeWorldBooks()) { values ->
            assertTrue(values.receive().isEmpty())
            val imported = WorldBookLibraryService(content).importJson("""{"name":"Lore","entries":[]}""", "lore.json")
            assertEquals(imported.worldBook, values.nextWhere { it.isNotEmpty() }.single())
            db.worldBooks().delete(imported.worldBook.id)
            assertTrue(values.nextWhere { it.isEmpty() }.isEmpty())
        }
    }

    @Test fun chatsObserveNewAndRenamedSessions() = runBlocking {
        observed(chats.observeSessions()) { values ->
            assertTrue(values.receive().isEmpty())
            chats.saveSession(chat("one"))
            assertEquals("one", values.nextWhere { it.size == 1 }.single().id)
            chats.saveSession(chat("two").copy(updatedAt = "2026-10-29T01:00:00Z"))
            assertEquals(listOf("two", "one"), values.nextWhere { it.size == 2 }.map { it.id })
            chats.saveSession(chat("one").copy(title = "Edited", updatedAt = "2026-10-29T02:00:00Z"))
            assertEquals("Edited", values.nextWhere { it.firstOrNull()?.id == "one" }.first().title)
            chats.deleteSession("one")
            assertEquals("two", values.nextWhere { it.size == 1 }.single().id)
        }
    }

    @Test fun messagePageObservesCandidateTextSelectionAndDeletionWithoutAnotherNodeWrite() = runBlocking {
        chats.saveSession(chat("chat")); chats.saveSession(chat("other"))
        observed(chats.observeMessages("chat", limit = 1)) { values ->
            assertTrue(values.receive().isEmpty())
            val node = chats.appendNode("chat", message("m", "first"))
            assertEquals("first", values.nextWhere { it.isNotEmpty() }.single().selected!!.content)
            val selected = chats.addCandidate(node.id, message("m2", "alternative"))
            val updated = values.nextWhere { it.firstOrNull()?.candidates?.size == 2 }.single()
            assertEquals("alternative", updated.selected!!.content)
            chats.updateCandidate("chat", selected.selected!!, selected.selected!!.copy(content = "edited"))
            assertEquals("edited", values.nextWhere { it.firstOrNull()?.selected?.content == "edited" }.single().selected!!.content)
            chats.appendNode("other", message("other-message", "private"))
            val latest = chats.appendNode("chat", message("m3", "newest"))
            assertEquals(latest.id, values.nextWhere { it.firstOrNull()?.id == latest.id }.single().id)
            assertEquals(node.id, chats.observeMessages("chat", 1, 1).first().single().id)
            chats.deleteMessageNode(latest.id)
            assertEquals(node.id, values.nextWhere { it.firstOrNull()?.id == node.id }.single().id)
        }
    }

    @Test fun typedSettingsSaveAndObservePreserveUnknownNestedFields() = runBlocking {
        val settings = AppSettingsService(content)
        content.saveSettingsJson("""{"future":{"keep":true},"generationDefaults":{"context_size":64000,"futureOption":7},"prompts":{"customPrompt":"keep"}}""", at)
        observed(settings.observe()) { values ->
            val previous = values.receive()
            assertEquals(64000, previous.generationDefaults.contextSize)
            settings.save(previous.copy(pureAiMode = true, generationDefaults = previous.generationDefaults.copy(contextSize = null)))
            val saved = values.nextWhere { it.pureAiMode }
            assertNull(saved.generationDefaults.contextSize)
            assertEquals(JsonPrimitive(7), saved.generationDefaults.extensions["futureOption"])
            assertEquals(JsonPrimitive("keep"), saved.prompts.extensions["customPrompt"])
            assertEquals(previous.extensions["future"], settings.get().extensions["future"])
        }
    }

    @Test fun standaloneImportCreatesIndependentBooksAndDoesNotActivateThem() = runBlocking {
        val library = WorldBookLibraryService(content)
        val raw = """{"id":"source","name":"Lore","globalActive":true,"sessionChatIds":["chat"],"entries":[{"id":"old","content":"facts","constantActive":true}]}"""
        val first = library.importJson(raw, "lore.json").worldBook
        val second = library.importJson(raw, "lore.json").worldBook
        assertNotEquals(first.id, second.id)
        assertNotEquals("old", first.entries.single().id)
        assertFalse(first.globalActive)
        assertTrue(first.sessionChatIds.isEmpty())
        assertEquals(first, content.getWorldBook(first.id))
        try { library.importJson("""{"entries":[null]}""", "bad.json"); fail("invalid file must fail") }
        catch (_: StError.Validation) { }
        assertEquals(2, content.listWorldBooks().size)
    }

    @Test fun standaloneFileInputIsBoundedReadAndPersisted() = runBlocking {
        val file = java.io.File.createTempFile("worldbook-", ".json")
        try {
            file.writeText("""{"name":"File lore","entries":[{"content":"facts","constantActive":true}]}""")
            val library = WorldBookLibraryService(content)
            val imported = library.importFile(file).worldBook
            assertEquals("File lore", content.getWorldBook(imported.id)!!.name)
            file.writeBytes(byteArrayOf(0xff.toByte()))
            try { library.importFile(file); fail("invalid UTF-8 must fail") } catch (_: StError.Validation) { }
            assertEquals(1, content.listWorldBooks().size)
        } finally { file.delete() }
    }

    @Test fun characterAttachmentIsIdempotentChecksStaleEditsAndActivatesForItsChats() = runBlocking {
        val library = WorldBookLibraryService(content)
        val hero = character("hero").copy(sourceNamespace = "desktop:source")
        content.saveCharacter(hero)
        val book = library.importJson("""{"entries":[]}""", "book.json").worldBook
        val attached = library.attach(hero.id, book.id, hero.updatedAt)
        assertEquals(listOf(book.id), attached.attachedWorldBookIds)
        assertEquals(attached, library.attach(hero.id, book.id, attached.updatedAt))
        assertEquals(listOf(book.id), content.activeWorldBooks(chat("chat").copy(sourceNamespace = hero.sourceNamespace), attached).map { it.id })
        try { library.detach(hero.id, book.id, hero.updatedAt); fail("stale edits must fail") } catch (_: StError.Conflict) { }
        assertEquals(attached, content.getCharacter(hero.id))
        try { library.attach(hero.id, "missing", attached.updatedAt); fail("missing book must fail") } catch (_: StError.NotFound) { }
        assertTrue(library.detach(hero.id, book.id, attached.updatedAt).attachedWorldBookIds.isEmpty())
    }

    @Test fun connectionProbeRecordsUsageWithoutCreatingChatOrTaskRows() = runBlocking {
        val provider = com.simpletavern.core.llm.ProviderConfig("preset", LlmProtocol.OPENAI_COMPATIBLE,
            "https://api.example/v1", "fixture-secret", "fixture-model", streaming = false)
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val body = """{"choices":[{"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":8,"completion_tokens":1}}"""
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = ConnectionTestService({ provider }, com.simpletavern.core.llm.LlmClient(http), UsageStore(db)).test("preset")
        assertTrue(result.message, result.success)
        val database = db.openHelper.readableDatabase
        database.query("SELECT usageJson FROM usage_events").use { cursor ->
            assertTrue(cursor.moveToFirst())
            val raw = cursor.getString(0)
            assertTrue(raw.contains("connection_test"))
            assertTrue(raw.contains("completed"))
            assertFalse(cursor.moveToNext())
        }
        assertTrue(chats.listSessions().isEmpty())
        database.query("SELECT COUNT(*) FROM tasks").use { cursor -> cursor.moveToFirst(); assertEquals(0, cursor.getInt(0)) }
    }
}
