package com.simpletavern.core.api

import com.simpletavern.core.data.*
import com.simpletavern.core.model.*
import kotlinx.serialization.json.*

internal class AssistantSpeechService(private val content: ContentRepository, private val chats: ChatRepository,
    private val playback: SpeechPlaybackService, private val gate: DataOperationGate) {
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(key: String, default: Double) = (get(key) as? JsonPrimitive)?.doubleOrNull ?: default

    suspend fun start(config: AssistantConfig, chatId: String, text: String): JsonObject = gate.write {
        val current = content.getAssistant(config.id) ?: throw StError.NotFound("assistant configuration")
        if (current.sourceNamespace != config.sourceNamespace) throw StError.Conflict("speech assistant source changed")
        val chat = chats.getSession(chatId) ?: throw StError.NotFound("speech chat")
        if (chat.sourceNamespace != current.sourceNamespace) throw StError.Conflict("speech chat source differs from assistant")
        val settings = Jsonx.decode<JsonObject>(current.settingsJson)
        val warnings = mutableListOf<String>()
        val explicit = chat.overrides.ttsJson?.let { Jsonx.decode<JsonObject>(it) } ?: settings["ttsJson"] as? JsonObject
        val raw = if (current.sourceNamespace != null) content.getSourceSettingsJson(current.sourceNamespace!!)
            else content.getSettingsJson()
        val root = raw?.let { Jsonx.decode<JsonObject>(it) } ?: JsonObject(emptyMap())
        if ((root["ttsEnabled"] as? JsonPrimitive)?.booleanOrNull == false) throw StError.Speech("tts_disabled", "TTS is disabled for this source")
        val sourceSpeed = root.number("defaultTTSPlaybackSpeed", 1.0).coerceIn(0.5, 2.0)
        val playbackSpeed = explicit?.number("playbackSpeed", sourceSpeed) ?: sourceSpeed
        val task = if (explicit != null) {
            if (explicit.text("provider") == "system") playback.startSystem(SystemSpeechRequest(text,
                explicit.number("speechRate", 1.0), explicit.number("pitch", 1.0), explicit.text("language"), chatId, current.id), playbackSpeed)
            else playback.startCloud(SpeechRequest(text, presetId = explicit.text("presetId"), model = explicit.text("model"),
                voiceId = explicit.text("voiceId"), chatId = chatId, format = explicit.text("format"),
                speed = explicit.number("speed", 1.0), instructions = explicit.text("instructions"),
                stability = (explicit["stability"] as? JsonPrimitive)?.doubleOrNull,
                similarityBoost = (explicit["similarityBoost"] as? JsonPrimitive)?.doubleOrNull,
                providerOptions = explicit["providerOptions"] as? JsonObject ?: JsonObject(emptyMap())),
                pcmFormat = (explicit["pcmFormat"] as? JsonObject)?.let {
                    PcmPlaybackFormat(it["sampleRate"]!!.jsonPrimitive.int, (it["channels"] as? JsonPrimitive)?.intOrNull ?: 1)
                }, playbackSpeed = playbackSpeed)
        } else {
            if (raw == null) throw StError.NotFound("speech source settings")
            val providers = (root["ttsProviders"] as? JsonArray).orEmpty().map { it.jsonObject }
            val selected = root.text("selectedTTSProviderId") ?: if (root["selectedTTSProviderId"] == JsonNull)
                providers.firstOrNull()?.text("id") ?: DEFAULT_SYSTEM else DEFAULT_SYSTEM
            val provider = providers.singleOrNull { it.text("id") == selected } ?: if (selected == DEFAULT_SYSTEM &&
                current.sourceNamespace?.substringBefore(':') == "rikka_hub") buildJsonObject { put("type", "system") }
                else throw StError.NotFound("selected source TTS provider")
            when (val type = provider.text("type")) {
                "system" -> playback.startSystem(SystemSpeechRequest(text, provider.number("speechRate", 1.0),
                    provider.number("pitch", 1.0), chatId = chatId, assistantId = current.id), playbackSpeed)
                "minimax", "openai", "groq", "gemini", "elevenlabs", "qwen", "xai", "fish-audio", "step", "mimo", "volcengine" -> {
                    val preset = content.listPresets().singleOrNull { it.sourceNamespace == current.sourceNamespace &&
                        Jsonx.decode<JsonObject>(it.configJson).text("sourceTTSProviderId") == selected }
                        ?: throw StError.NotFound("source TTS preset; explicitly reimport its configuration")
                    val presetConfig = Jsonx.decode<JsonObject>(preset.configJson)
                    val options = JsonObject(presetConfig["sourceTTS"]!!.jsonObject + (presetConfig["providerOptions"] as? JsonObject).orEmpty())
                    val defaults = when (type) {
                        "openai" -> "gpt-4o-mini-tts" to "alloy"
                        "groq" -> "canopylabs/orpheus-v1-english" to "austin"
                        "gemini" -> "gemini-2.5-flash-preview-tts" to "Kore"
                        "elevenlabs" -> "eleven_multilingual_v2" to "JBFqnCBsd6RMkjVDRZzb"
                        "qwen" -> "qwen-audio-3.0-tts-flash" to "longanhuan_v3.6"
                        "xai" -> "xai-tts" to "eve"
                        "fish-audio" -> "s2.1-pro" to ""
                        "step" -> "step-tts-mini" to "elegantgentle-female"
                        "mimo" -> "mimo-v2.5-tts" to "mimo_default"
                        "volcengine" -> (options.text("resourceId") ?: "seed-tts-2.0") to "zh_female_vv_uranus_bigtts"
                        else -> "speech-2.8-hd" to "female-shaonv"
                    }
                    val format = presetConfig.text("format") ?: if (type == "mimo") "wav"
                        else options.text("format") ?: options.text("responseFormat") ?: SpeechProviders.defaultFormat(type)
                    val sampleRate = (presetConfig["sampleRate"] as? JsonPrimitive)?.intOrNull ?: (options["sampleRate"] as? JsonPrimitive)?.intOrNull
                        ?: when (type) { "qwen", "step" -> 24000; "fish-audio" -> if (format == "opus") 48000 else 44100; else -> null }
                    playback.startCloud(SpeechRequest(text, preset.id, presetConfig.text("model") ?: options.text("model") ?: defaults.first,
                        presetConfig.text("voiceId") ?: options.text("voiceId") ?: options.text("voice") ?: options.text("voiceName") ?:
                            options.text("referenceId") ?: options.text("speaker") ?: defaults.second,
                        chatId = chatId, format = format, sampleRate = sampleRate,
                        speed = presetConfig.number("speed", options.number("speed", 1.0)),
                        volume = presetConfig.number("volume", options.number("volume", 1.0)),
                        instructions = presetConfig.text("instructions") ?: if (type == "step") options.text("instruction") else null,
                        stability = if (type == "elevenlabs") presetConfig.number("stability", options.number("stability", 0.5)) else null,
                        similarityBoost = if (type == "elevenlabs") presetConfig.number("similarityBoost", options.number("similarityBoost", 0.75)) else null,
                        providerOptions = options),
                        pcmFormat = if (format == "pcm" && type in setOf("qwen", "step", "fish-audio") && sampleRate != null) PcmPlaybackFormat(sampleRate) else null,
                        playbackSpeed = playbackSpeed)
                }
                else -> throw StError.Unsupported("source TTS provider $type still needs its runtime adapter; no system fallback")
            }
        }
        buildJsonObject { put("success", true); put("task_id", task.id); put("status", "queued")
            put("warnings", JsonArray(warnings.map(::JsonPrimitive)))
            put("note", "AI 生成语音的播放任务已启动，不代表音频已经播放完成；可按 task_id 查看或停止。") }
    }
    companion object { private const val DEFAULT_SYSTEM = "026a01a2-c3a0-4fd5-8075-80e03bdef200" }
}
