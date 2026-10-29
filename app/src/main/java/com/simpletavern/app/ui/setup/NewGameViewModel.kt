package com.simpletavern.app.ui.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simpletavern.app.StApplication
import com.simpletavern.core.model.CharacterCard
import com.simpletavern.core.model.ModelPreset
import com.simpletavern.core.model.StError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class CharacterDraft(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
)

/** Only what a connection needs: protocol, endpoint, key and model. */
data class PresetDraft(
    val name: String = "",
    val protocol: String = Protocols.first().id,
    val baseUrl: String = Protocols.first().base,
    val model: String = "",
    val key: String = "",
)

data class ProtocolChoice(val id: String, val label: String, val base: String)

val Protocols = listOf(
    ProtocolChoice("openai_compatible_chat", "OpenAI 兼容", "https://api.deepseek.com"),
    ProtocolChoice("anthropic_messages", "Anthropic", "https://api.anthropic.com"),
    ProtocolChoice("gemini_generate_content", "Gemini", "https://generativelanguage.googleapis.com"),
    ProtocolChoice("openai_responses", "OpenAI Responses", "https://api.openai.com"),
)

data class Line(val text: String, val failed: Boolean)

/** A fresh start without desktop data: write a character, set up a model preset, deal a new chat. */
class NewGameViewModel(app: Application) : AndroidViewModel(app) {
    private val core = (app as StApplication).core

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _line = MutableStateFlow<Line?>(null)
    val line: StateFlow<Line?> = _line.asStateFlow()

    val characters: StateFlow<List<CharacterCard>> = core.observeCharacters()
        .catch { e -> _line.value = Line(e.readable(), true); emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val presets: StateFlow<List<ModelPreset>> = core.observePresets()
        .catch { e -> _line.value = Line(e.readable(), true); emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun clearLine() { _line.value = null }

    fun saveCharacter(d: CharacterDraft, onSaved: (String) -> Unit) {
        if (d.name.isBlank()) { _line.value = Line("角色至少要有个名字。", true); return }
        run {
            val now = Instant.now().toString()
            val card = CharacterCard(
                id = newId(),
                name = d.name.trim(),
                description = d.description.trim(),
                personality = d.personality.trim(),
                scenario = d.scenario.trim(),
                firstMessage = d.firstMessage.trim(),
                createdAt = now,
                updatedAt = now,
            )
            core.saveCharacter(card)
            _line.value = Line("「${card.name}」收进牌盒了。", false)
            onSaved(card.id)
        }
    }

    fun savePreset(d: PresetDraft, onSaved: (String) -> Unit) {
        val missing = listOfNotNull(
            "地址".takeIf { d.baseUrl.isBlank() },
            "模型".takeIf { d.model.isBlank() },
            "API Key".takeIf { d.key.isBlank() },
        )
        if (missing.isNotEmpty()) { _line.value = Line("还差：${missing.joinToString("、")}", true); return }
        run {
            val now = Instant.now().toString()
            val id = newId()
            val config = JSONObject()
                .put("protocol", d.protocol)
                .put("baseUrl", d.baseUrl.trim().trimEnd('/'))
                .put("model", d.model.trim())
            core.savePreset(ModelPreset(id, d.name.trim().ifBlank { d.model.trim() }, config.toString(), now, now))
            // Without an explicit credentialId the preset's own ID is its key slot.
            core.setProviderCredential(id, d.key.trim())
            _line.value = Line("预设已存下。", false)
            onSaved(id)
        }
    }

    /** Sends one real (billable) request, so it only ever runs from the explicit button. */
    fun testPreset(presetId: String) {
        run {
            _line.value = Line("正在试牌…", false)
            val r = core.testConnection(presetId)
            _line.value = if (r.success) Line("通了 · ${r.latencyMs} ms" + (r.model?.let { " · $it" } ?: ""), false)
            else Line("没通 · " + listOfNotNull(r.errorCode, r.message).joinToString(" · ").ifBlank { "未知错误" }, true)
        }
    }

    fun start(characterId: String, presetId: String, title: String, onStarted: (String) -> Unit) {
        run {
            val chat = core.createChat(characterId, title.trim().ifBlank { "新对话" })
            core.saveChat(chat.copy(overrides = chat.overrides.copy(presetId = presetId), updatedAt = Instant.now().toString()))
            _line.value = null
            onStarted(chat.id)
        }
    }

    /** One write at a time; failures land on the status line. */
    private fun run(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            runCatching { block() }.onFailure { e -> _line.value = Line(e.readable(), true) }
            _busy.value = false
        }
    }

    private fun newId() = UUID.randomUUID().toString().replace("-", "")

    private fun Throwable.readable(): String = (this as? StError)?.message ?: message ?: javaClass.simpleName
}
