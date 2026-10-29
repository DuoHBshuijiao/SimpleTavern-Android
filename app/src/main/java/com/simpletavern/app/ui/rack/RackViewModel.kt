package com.simpletavern.app.ui.rack

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simpletavern.app.StApplication
import com.simpletavern.core.model.CharacterCard
import com.simpletavern.core.model.ChatSession
import com.simpletavern.core.model.StError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

/** How recently a session was played, shown as a soft glow on its plate. */
enum class Recency { Warm, Cool, None }

data class RackSession(val id: String, val title: String, val recency: Recency, val presetId: String?)

data class RackColumn(val characterId: String, val name: String, val hue: Float, val sessions: List<RackSession>)

data class RackUiState(
    val loaded: Boolean = false,
    val columns: List<RackColumn> = emptyList(),
    val notice: String? = null,
)

/** The home shelf: characters left to right (most recently played first), their sessions top to bottom (newest first). */
class RackViewModel(app: Application) : AndroidViewModel(app) {
    private val core = (app as StApplication).core
    private val notice = MutableStateFlow<String?>(null)
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val state: StateFlow<RackUiState> = combine(
        core.observeCharacters().catch { e -> notice.value = e.readable(); emit(emptyList()) },
        core.observeChats().catch { e -> notice.value = e.readable(); emit(emptyList()) },
        notice,
    ) { characters, chats, nt -> RackUiState(loaded = true, columns = shelve(characters, chats), notice = nt) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RackUiState())

    fun clearNotice() { notice.value = null }

    /**
     * Deals a fresh session for a character, on the connection its latest session used, else the first preset.
     * With no preset at all the caller has to set one up first.
     */
    fun newSession(characterId: String, onCreated: (String) -> Unit, onNeedsPreset: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            runCatching {
                val column = state.value.columns.firstOrNull { it.characterId == characterId }
                val presetId = column?.sessions?.firstNotNullOfOrNull { it.presetId }
                    ?: core.listPresets().firstOrNull()?.id
                if (presetId == null) { onNeedsPreset(); return@runCatching }
                val chat = core.createChat(characterId)
                core.saveChat(chat.copy(overrides = chat.overrides.copy(presetId = presetId), updatedAt = Instant.now().toString()))
                onCreated(chat.id)
            }.onFailure { e -> notice.value = e.readable() }
            _busy.value = false
        }
    }

    /** Discards a session and every card in it. */
    fun deleteSession(id: String) {
        viewModelScope.launch { runCatching { core.deleteChat(id) }.onFailure { e -> notice.value = e.readable() } }
    }

    private fun shelve(characters: List<CharacterCard>, chats: List<ChatSession>): List<RackColumn> {
        val now = Instant.now()
        val byCharacter = chats.groupBy { it.characterId }
        val latest = byCharacter.mapValues { (_, list) -> list.maxOf { it.updatedAt } }
        return characters
            .sortedWith(compareByDescending<CharacterCard> { latest[it.id] ?: "" }.thenBy { it.name })
            .map { c ->
                val sessions = byCharacter[c.id].orEmpty().sortedByDescending { it.updatedAt }.map { s ->
                    RackSession(s.id, s.title.ifBlank { "无题" }, recency(s.updatedAt, now), s.overrides.presetId)
                }
                RackColumn(c.id, c.name.ifBlank { "无名" }, hueOf(c.id), sessions)
            }
    }

    private fun recency(at: String, now: Instant): Recency {
        val then = runCatching { Instant.parse(at) }.getOrNull() ?: return Recency.None
        val days = (now.epochSecond - then.epochSecond) / 86_400.0
        return when {
            days <= 3 -> Recency.Warm
            days <= 7 -> Recency.Cool
            else -> Recency.None
        }
    }

    private fun Throwable.readable(): String = (this as? StError)?.message ?: message ?: javaClass.simpleName
}

/** A stable theme hue per character until characters carry their own color. */
fun hueOf(id: String): Float = ((id.hashCode() and 0x7fffffff) % 360).toFloat()
