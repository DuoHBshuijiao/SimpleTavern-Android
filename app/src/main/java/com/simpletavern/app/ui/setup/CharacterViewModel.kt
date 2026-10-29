package com.simpletavern.app.ui.setup

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simpletavern.app.StApplication
import com.simpletavern.core.model.CharacterCard
import com.simpletavern.core.model.StError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * The fields of a character card, as the desktop editor has them. Group play, MVU and regex stay in the backend
 * for now; everything here takes part in the conversation.
 */
data class CharacterDraft(
    val name: String = "",
    /** 简介: a short description. */
    val description: String = "",
    /** 性格/外貌: the detailed setting. */
    val personality: String = "",
    /** 情景/世界观. */
    val scenario: String = "",
    val systemPrompt: String = "",
    /** 首句, supports {{user}}. */
    val firstMessage: String = "",
    /** 额外首句: other openings a new session can start from. */
    val extraFirstMessages: List<String> = emptyList(),
    val exampleDialogue: String = "",
)

data class CharacterEdit(
    val loaded: Boolean = false,
    val id: String? = null,
    val draft: CharacterDraft = CharacterDraft(),
    val avatar: ImageBitmap? = null,
    val busy: Boolean = false,
    val line: Line? = null,
)

/** Writes and edits one character card. Unknown fields of an existing card are carried through untouched. */
class CharacterViewModel(app: Application) : AndroidViewModel(app) {
    private val core = (app as StApplication).core
    private val _state = MutableStateFlow(CharacterEdit())
    val state: StateFlow<CharacterEdit> = _state.asStateFlow()
    private var original: CharacterCard? = null

    fun open(id: String?) {
        original = null
        if (id == null) { _state.value = CharacterEdit(loaded = true); return }
        _state.value = CharacterEdit(loaded = false, id = id)
        viewModelScope.launch {
            runCatching { core.getCharacter(id) }
                .onSuccess { c ->
                    original = c
                    _state.value = CharacterEdit(loaded = true, id = c.id, draft = c.toDraft(), avatar = avatarOf(c))
                }
                .onFailure { e -> _state.value = CharacterEdit(loaded = true, line = Line(e.readable(), true)) }
        }
    }

    fun edit(d: CharacterDraft) { _state.value = _state.value.copy(draft = d, line = null) }

    /** Saves the card; a new one gets its ID here. */
    fun save(onSaved: (String) -> Unit) {
        val s = _state.value
        if (s.draft.name.isBlank()) { _state.value = s.copy(line = Line("角色至少要有个名字。", true)); return }
        work {
            val saved = persist(s.draft)
            _state.value = _state.value.copy(id = saved.id, line = Line("「${saved.name}」收进牌盒了。", false))
            onSaved(saved.id)
        }
    }

    /** Sets the portrait. A new card is saved first so the portrait has somewhere to belong. */
    fun setAvatar(uri: Uri) {
        val s = _state.value
        if (s.draft.name.isBlank()) { _state.value = s.copy(line = Line("先写名字，再放头像。", true)); return }
        work {
            val id = s.id ?: persist(s.draft).id
            val (name, mime) = describe(uri)
            core.setCharacterAvatar(id, uri, name, mime)
            val c = core.getCharacter(id)
            original = c
            _state.value = _state.value.copy(id = id, avatar = avatarOf(c), line = null)
        }
    }

    private suspend fun persist(d: CharacterDraft): CharacterCard {
        val now = Instant.now().toString()
        val base = original?.let { core.getCharacter(it.id) }
        val card = (base ?: CharacterCard(id = UUID.randomUUID().toString().replace("-", ""), createdAt = now, updatedAt = now)).copy(
            name = d.name.trim(),
            description = d.description.trim(),
            personality = d.personality.trim(),
            scenario = d.scenario.trim(),
            systemPrompt = d.systemPrompt.trim(),
            firstMessage = d.firstMessage.trim(),
            exampleDialogue = d.exampleDialogue.trim(),
            extraJson = withExtraFirst(base?.extraJson, d.extraFirstMessages),
            updatedAt = now,
        )
        core.saveCharacter(card)
        original = card
        return card
    }

    private fun CharacterCard.toDraft(): CharacterDraft {
        val extra = runCatching { JSONObject(extraJson ?: "{}").optJSONArray("extraFirstMessageEntries") }.getOrNull()
        val alternates = buildList {
            if (extra != null) for (i in 0 until extra.length()) {
                val o = extra.optJSONObject(i) ?: continue
                if (o.has("chip") && !o.optBoolean("chip")) continue
                o.optString("text").trim().takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
        return CharacterDraft(name, description, personality, scenario, systemPrompt, firstMessage, alternates, exampleDialogue)
    }

    /** Alternate openings live in extraJson the way the desktop stores them (saved entries are "chips"). */
    private fun withExtraFirst(extraJson: String?, entries: List<String>): String? {
        val root = runCatching { JSONObject(extraJson ?: "{}") }.getOrDefault(JSONObject())
        val kept = entries.map { it.trim() }.filter { it.isNotEmpty() }
        if (kept.isEmpty() && !root.has("extraFirstMessageEntries")) return extraJson
        root.put("extraFirstMessageEntries", JSONArray().apply { kept.forEach { put(JSONObject().put("text", it).put("chip", true)) } })
        return root.toString()
    }

    private suspend fun avatarOf(c: CharacterCard): ImageBitmap? = c.avatarRelativePath?.let { path ->
        runCatching {
            val file = core.getAvatarFile(path)
            withContext(Dispatchers.IO) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
            }
        }.getOrNull()
    }

    private fun describe(uri: Uri): Pair<String, String> {
        val resolver = getApplication<Application>().contentResolver
        val mime = resolver.getType(uri) ?: "image/png"
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "avatar.${mime.substringAfter('/', "png")}"
        return name to mime
    }

    private fun work(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            runCatching { block() }.onFailure { e -> _state.value = _state.value.copy(line = Line(e.readable(), true)) }
            _state.value = _state.value.copy(busy = false)
        }
    }

    private fun Throwable.readable(): String = (this as? StError)?.message ?: message ?: javaClass.simpleName
}
