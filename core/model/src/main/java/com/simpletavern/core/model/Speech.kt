package com.simpletavern.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SpeechRequest(val text: String, val presetId: String? = null, val model: String? = null,
    val voiceId: String? = null, val chatId: String? = null, val assistantId: String? = null,
    val characterId: String? = null, val personaId: String? = null, val format: String? = null,
    val speed: Double = 1.0, val volume: Double = 1.0, val pitch: Int = 0,
    val emotion: String? = null, val sampleRate: Int? = null, val gain: Double? = null,
    val instructions: String? = null, val stability: Double? = null, val similarityBoost: Double? = null,
    val providerOptions: JsonObject = JsonObject(emptyMap())) {
    override fun toString() = "SpeechRequest(text=<private>, selection=<private>)"
}

object SpeechProviders {
    val cloud = setOf("minimax", "glm", "openrouter", "siliconflow", "openai", "groq", "gemini", "elevenlabs",
        "qwen", "xai", "fish-audio", "step", "mimo", "volcengine")
    val local = setOf("glm_local", "qwen3_local", "omnivoice_local")
    val supported = cloud + local
    fun defaultFormat(provider: String) = if (provider in setOf("glm", "groq", "gemini", "qwen", "mimo") || provider in local) "wav" else "mp3"
    fun validVoice(provider: String, voice: String) = (voice.isNotBlank() || provider == "fish-audio") &&
        voice.length <= 512 && voice.none(Char::isISOControl)
    fun textLimit(provider: String, model: String) = when (provider) {
        "openai" -> 4096; "glm" -> 1024; "step" -> 1000; "minimax" -> 9999
        "groq" -> if (model.startsWith("canopylabs/orpheus-")) 200 else 10000
        else -> 10000
    }
}

/** In-process transport audio; never serialize bytes or expose them in ordinary task logs. */
class SpeechAudioChunk(val format: String, val sampleRate: Int?, val bytes: ByteArray, val channels: Int? = null) {
    override fun toString() = "SpeechAudioChunk(format=$format, bytes=${bytes.size}, contents=<omitted>)"
}

object SpeechSegments {
    fun split(text: String, maxCharacters: Int): List<String> = buildList {
        var start = 0
        while (start < text.length) {
            val count = text.codePointCount(start, text.length)
            var end = text.offsetByCodePoints(start, minOf(count, maxCharacters))
            if (end < text.length) {
                val earliest = text.offsetByCodePoints(start, maxCharacters / 2)
                val boundary = (end - 1 downTo earliest).firstOrNull { text[it] in "。！？.!?；;\n" }
                if (boundary != null) end = boundary + 1
            }
            val part = text.substring(start, end)
            if (part.isNotBlank()) add(part) // standalone whitespace is not a spoken segment.
            start = end
        }
    }
}
@Serializable
data class SpeechSelection(val presetId: String, val assistantId: String?, val provider: String,
    val model: String, val voiceId: String, val baseUrl: String, val format: String,
    val credentialId: String, val hasCredential: Boolean, val cacheLimitBytes: Long = 209_715_200,
    val sourceNamespace: String? = null, val preprocessing: SpeechPreprocessSelection? = null,
    val preprocessingTaskId: String? = null)

@Serializable
data class SpeechAsset(val id: String, val provider: String, val model: String, val voiceId: String,
    val format: String, val sampleRate: Int?, val bytes: Long, val createdAt: String,
    val assistantId: String? = null, val chatId: String? = null, val messageCandidateId: String? = null,
    val reportedUsageCharacters: Long? = null, val warnings: List<String> = emptyList(), val version: Int = 1,
    val spokenText: String? = null, val sourceNamespace: String? = null, val requestId: String? = null,
    val preprocessingRequestId: String? = null) {
    override fun toString() = "SpeechAsset(id=$id, format=$format, bytes=$bytes, spokenText=<private>)"
}

@Serializable
data class SpeechTaskProgress(val taskId: String, val selection: SpeechSelection, val updatedAt: String,
    val phase: String = "queued", val messageCandidateId: String? = null, val asset: SpeechAsset? = null,
    val errorCode: String? = null, val device: Long? = null, val inode: Long? = null, val version: Int = 1,
    val preprocessingRequestId: String? = null)

@Serializable
data class SpeechCacheStats(val assistantId: String?, val files: Long, val bytes: Long, val missingFiles: Long)

@Serializable
data class SpeechCacheClearResult(val files: Long, val bytes: Long, val retainedAssets: Long = 0)

@Serializable
data class SpeechMessageBinding(val assetId: String, val sourceText: String, val version: Int = 1,
    val messageText: String? = null) {
    override fun toString() = "SpeechMessageBinding(assetId=$assetId, text=<private>)"
}

@Serializable
data class SpeechBindRequest(val assetId: String, val chatId: String, val messageCandidateId: String,
    val expectedMessageContent: String, val expectedAssetId: String? = null) {
    override fun toString() = "SpeechBindRequest(assetId=$assetId, message=<private>)"
}

object SpeechText {
    fun validate(text: String, byteBudget: Int, spoken: Boolean = false) {
        if (text.length > byteBudget) throw StError.Validation("speech text exceeds read budget")
        var index = 0
        while (index < text.length) {
            val ch = text[index++]
            if (ch.isHighSurrogate()) {
                if (index >= text.length || !text[index++].isLowSurrogate()) throw StError.Validation("invalid speech text Unicode")
            } else if (ch.isLowSurrogate()) throw StError.Validation("invalid speech text Unicode")
        }
        if (text.toByteArray(Charsets.UTF_8).size > byteBudget || spoken && (text.isBlank() || text.codePointCount(0, text.length) > 10_000 ||
                text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }))
            throw StError.Validation("speech text exceeds budget or contains invalid controls")
    }
}

/** Shared native-restore/live-catalog contract, not a content checksum. */
object SpeechTaskMetadata {
    const val MAX_PAYLOAD_BYTES = 131_072
    fun validateBinding(binding: SpeechMessageBinding) {
        if (binding.version !in 1..2 || !binding.assetId.matches(Regex("[0-9a-f]{32}")) || binding.version == 2 && binding.messageText == null)
            throw StError.Validation("invalid speech message binding")
        SpeechText.validate(binding.sourceText, 40_000, spoken = true)
        binding.messageText?.let { SpeechText.validate(it, 131_072) }
    }
    fun validate(value: SpeechTaskProgress) {
        fun identity(id: String) = id.isNotBlank() && id.toByteArray(Charsets.UTF_8).size <= 1024 && id.none(Char::isISOControl)
        if (value.version != 1 || !value.taskId.matches(Regex("[0-9a-f]{32}")) ||
            value.phase !in setOf("queued", "preprocessing", "synthesizing", "staging", "publishing", "done", "failed", "stopped", "interrupted") ||
            (value.device == null) != (value.inode == null)) throw StError.Validation("invalid speech task metadata")
        val selection = value.selection
        if (selection.provider !in SpeechProviders.supported ||
            selection.format !in setOf("mp3", "wav", "pcm", "opus", "flac", "aac") ||
            selection.cacheLimitBytes !in 10_485_760..10_485_760_000 ||
            listOfNotNull(selection.presetId, selection.assistantId, selection.credentialId, selection.sourceNamespace, value.messageCandidateId).any { !identity(it) } ||
            selection.model.let { it.isBlank() || it.length > 512 || it.any(Char::isISOControl) } || !SpeechProviders.validVoice(selection.provider, selection.voiceId) ||
            selection.baseUrl.length > 4096 || value.errorCode?.let { it.length > 128 || it.any(Char::isISOControl) } == true)
            throw StError.Validation("invalid speech task selection")
        selection.preprocessing?.let { preprocessing ->
            SpeechPreprocessMetadata.validate(preprocessing)
            if (preprocessing.presetId != selection.presetId || preprocessing.assistantId != selection.assistantId ||
                preprocessing.sourceNamespace != selection.sourceNamespace || preprocessing.speechProvider != selection.provider ||
                preprocessing.speechModel != selection.model) throw StError.Validation("speech preprocessing selection differs from synthesis")
        }
        if (selection.preprocessingTaskId != null &&
            (!selection.preprocessingTaskId.matches(Regex("[0-9a-f]{32}")) || selection.preprocessing == null))
            throw StError.Validation("invalid speech preprocessing task identity")
        if (value.preprocessingRequestId != null && (!SpeechPreprocessMetadata.validRequestId(value.preprocessingRequestId) || selection.preprocessing == null) ||
            value.phase == "preprocessing" && selection.preprocessing == null)
            throw StError.Validation("invalid speech preprocessing progress")
        try { java.time.Instant.parse(value.updatedAt) } catch (_: Exception) { throw StError.Validation("invalid speech progress time") }
        val asset = value.asset
        if (value.phase == "done" && asset == null) throw StError.Validation("completed speech task needs an asset")
        if (asset != null) {
            if (asset.version !in 1..2 || asset.id != value.taskId || asset.assistantId != selection.assistantId ||
                asset.messageCandidateId != value.messageCandidateId || asset.provider != selection.provider || asset.format != selection.format ||
                asset.model != selection.model || asset.voiceId != selection.voiceId || asset.messageCandidateId != null && asset.chatId == null ||
                asset.bytes !in 1..16_777_216 || asset.sampleRate?.let { it !in 8000..384000 } == true ||
                asset.reportedUsageCharacters?.let { it < 0 } == true || asset.chatId?.let { !identity(it) } == true ||
                asset.warnings.size > 32 || asset.warnings.any { it.length > 128 || it.any(Char::isISOControl) } ||
                asset.model.let { it.isBlank() || it.length > 512 || it.any(Char::isISOControl) } || !SpeechProviders.validVoice(asset.provider, asset.voiceId))
                throw StError.Validation("invalid speech asset metadata")
            if (asset.version == 2 && (asset.spokenText == null || asset.sourceNamespace != selection.sourceNamespace))
                throw StError.Validation("speech asset needs its frozen transcript/source")
            if (asset.requestId?.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) == false)
                throw StError.Validation("invalid speech usage request identity")
            if (asset.preprocessingRequestId != value.preprocessingRequestId ||
                (selection.preprocessing != null) != (asset.preprocessingRequestId != null))
                throw StError.Validation("speech asset preprocessing proof missing or inconsistent")
            asset.spokenText?.let { SpeechText.validate(it, 40_000, spoken = true) }
            try { java.time.Instant.parse(asset.createdAt) } catch (_: Exception) { throw StError.Validation("invalid speech asset time") }
        }
    }
}
/** Caller-owned audio; never serialize it into configuration/task JSON or logs. */
class SpeechAudio(val provider: String, val model: String, val voiceId: String, val format: String,
    val sampleRate: Int?, val bytes: ByteArray, val reportedUsageCharacters: Long? = null,
    val warnings: List<String> = emptyList(), val requestId: String? = null, val spokenText: String? = null,
    val preprocessingRequestId: String? = null) {
    override fun toString() = "SpeechAudio(provider=$provider, format=$format, bytes=${bytes.size}, contents=<omitted>)"
}
