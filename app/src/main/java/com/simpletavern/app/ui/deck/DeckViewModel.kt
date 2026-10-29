package com.simpletavern.app.ui.deck

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simpletavern.app.StApplication
import com.simpletavern.core.model.ChatRole
import com.simpletavern.core.model.MessageCandidate
import com.simpletavern.core.model.MessageNode
import com.simpletavern.core.model.StError
import com.simpletavern.core.model.StreamEvent
import com.simpletavern.core.model.TaskKind
import com.simpletavern.core.model.TaskSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One card in the deck, oldest first. */
data class DeckCard(
    val key: String,
    val nodeId: String?,
    val role: ChatRole,
    val who: String,
    val text: String,
    val candidateIndex: Int,
    val candidateCount: Int,
    val live: Boolean,
    val thinking: Boolean,
)

data class DeckUiState(
    val loaded: Boolean = false,
    val chatId: String? = null,
    val title: String = "",
    val characterName: String = "",
    val cards: List<DeckCard> = emptyList(),
    /** The chat [cards] were read from; differs from [chatId] while a newly opened deck is still loading. */
    val cardsFor: String? = null,
    val generating: Boolean = false,
    val notice: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DeckViewModel(app: Application) : AndroidViewModel(app) {
    private val core = (app as StApplication).core

    private data class Header(val chatId: String, val title: String, val characterName: String)
    private data class Live(val text: String, val reasoning: Boolean, val targetNodeId: String?)

    private val chatId = MutableStateFlow<String?>(null)
    private val live = MutableStateFlow<Live?>(null)
    private val notice = MutableStateFlow<String?>(null)

    private val header = chatId.filterNotNull()
        .mapLatest { id ->
            val chat = core.getChat(id)
            val name = runCatching { core.getCharacter(chat.characterId).name }.getOrDefault("")
            Header(id, chat.title, name)
        }
        .catch { e -> notice.value = e.readable() }

    private val nodes: Flow<Pair<String?, List<MessageNode>>> = chatId
        .flatMapLatest { id ->
            if (id == null) flowOf(null to emptyList())
            else core.observeMessages(id, limit = 500).map { id to it.asReversed() }
        }
        .catch { e -> notice.value = e.readable(); emit(null to emptyList()) }

    private val task = combine(chatId, core.observeActiveTasks()) { id, tasks -> tasks.firstOrNull { it.isGenerationOf(id) } }
        .catch { emit(null) }

    val state: StateFlow<DeckUiState> = combine(
        header.map<Header, Header?> { it }.onStart { emit(null) }, nodes, live, task, notice,
    ) { h, (forId, ns), lv, t, nt ->
        val current = h?.takeIf { it.chatId == chatId.value }
        val name = current?.characterName.orEmpty()
        DeckUiState(
            loaded = true,
            chatId = chatId.value,
            title = current?.title.orEmpty(),
            characterName = name,
            cards = buildCards(ns, name, lv),
            cardsFor = forId,
            generating = t != null || lv != null,
            notice = nt,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeckUiState())

    @Volatile private var taskId: String? = null
    private var listenJob: Job? = null

    init {
        viewModelScope.launch { task.collect { taskId = it?.id } }
        viewModelScope.launch { chatId.filterNotNull().collect { id -> listen(id) } }
    }

    /** Streams deltas into the live card. Events replayed right after subscribing are history, so their errors stay quiet. */
    private fun listen(id: String) {
        listenJob?.cancel()
        live.value = null
        val subscribedAt = SystemClock.elapsedRealtime()
        listenJob = viewModelScope.launch {
            core.observeGeneration(id).collect { ev ->
                when (ev) {
                    is StreamEvent.Delta -> live.update { cur ->
                        val base = cur ?: Live("", false, null)
                        if (ev.reasoning) base.copy(reasoning = true) else base.copy(text = base.text + ev.text, reasoning = false)
                    }
                    is StreamEvent.Done -> live.value = if (ev.continuation) live.value?.copy(text = "", reasoning = false) else null
                    is StreamEvent.Error -> {
                        live.value = null
                        if (SystemClock.elapsedRealtime() - subscribedAt > 400) notice.value = ev.message
                    }
                    else -> Unit
                }
            }
        }
    }

    fun openChat(id: String) {
        if (id != chatId.value) chatId.value = id
    }

    fun send(text: String): Boolean {
        val id = chatId.value ?: return false
        val body = text.trim()
        if (body.isEmpty()) return false
        if (state.value.generating) { notice.value = "上一张牌还没落定"; return false }
        live.value = Live("", false, null)
        viewModelScope.launch {
            runCatching { core.startGeneration(id, body) }
                .onFailure { e -> live.value = null; notice.value = e.readable() }
        }
        return true
    }

    fun stop() {
        val t = taskId ?: run { live.value = null; return }
        viewModelScope.launch { runCatching { core.stopTask(t) }.onFailure { e -> notice.value = e.readable() } }
    }

    /** dir = +1 next candidate (swipe left), −1 previous. Past the last candidate of the newest reply, redraw it. */
    fun swipe(card: DeckCard, dir: Int, isNewest: Boolean) {
        val id = chatId.value ?: return
        val nodeId = card.nodeId ?: return
        if (card.live) return
        val next = card.candidateIndex + dir
        viewModelScope.launch {
            runCatching {
                when {
                    next in 0 until card.candidateCount -> core.selectCandidate(nodeId, next)
                    dir > 0 && isNewest && card.role == ChatRole.assistant && !state.value.generating -> {
                        live.value = Live("", false, nodeId)
                        core.startRegeneration(id, nodeId)
                    }
                    else -> Unit
                }
            }.onFailure { e -> live.value = null; notice.value = e.readable() }
        }
    }

    fun clearNotice() { notice.value = null }

    private fun buildCards(nodes: List<MessageNode>, characterName: String, lv: Live?): List<DeckCard> {
        val out = ArrayList<DeckCard>(nodes.size + 1)
        for (node in nodes) {
            val sel = node.selected ?: continue
            if (sel.role != ChatRole.user && sel.role != ChatRole.assistant) continue
            if (lv != null && lv.targetNodeId == node.id) {
                out += DeckCard(node.id, node.id, ChatRole.assistant, whoOf(sel, characterName), lv.text,
                    node.candidates.size, node.candidates.size + 1, live = true, thinking = lv.reasoning && lv.text.isEmpty())
                continue
            }
            if (sel.content.isBlank() && sel.images.isEmpty() && sel.attachments.isEmpty()) continue
            out += DeckCard(node.id, node.id, sel.role, whoOf(sel, characterName), sel.content,
                node.selectedIndex, node.candidates.size, live = false, thinking = false)
        }
        if (lv != null && lv.targetNodeId == null) {
            out += DeckCard("live", null, ChatRole.assistant, characterName, lv.text, 0, 1, live = true,
                thinking = lv.reasoning && lv.text.isEmpty())
        }
        return out
    }

    private fun whoOf(c: MessageCandidate, characterName: String) = when (c.role) {
        ChatRole.user -> c.senderName ?: "你"
        else -> c.senderName ?: characterName
    }

    private fun TaskSnapshot.isGenerationOf(id: String?) =
        id != null && chatId == id && (kind == TaskKind.CHAT_GENERATION || kind == TaskKind.GROUP_GENERATION)

    private fun Throwable.readable(): String = (this as? StError)?.message ?: message ?: javaClass.simpleName
}
