package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SpeechVoiceDesignRequest(
    val prompt: String,
    val previewText: String,
    val presetId: String? = null,
    val chatId: String? = null,
    val assistantId: String? = null,
    val voiceId: String? = null,
) {
    override fun toString() =
        "SpeechVoiceDesignRequest(prompt=<private>, previewText=<private>, presetId=$presetId, chatId=$chatId, assistantId=$assistantId, voiceId=$voiceId)"
}

@Serializable
data class SpeechVoiceCreationResult(
    val voiceId: String,
    val previewAttachment: AttachmentRef? = null,
    val voiceType: String = "voice_generation",
    val warnings: List<String> = listOf(
        "voice_design_cost_unknown",
        "voice_design_not_synthesis_availability_proof",
    ),
    val remotePreviewUrl: String? = null,
) {
    override fun toString() =
        "SpeechVoiceCreationResult(voiceId=$voiceId, voiceType=$voiceType, warnings=$warnings, remotePreviewUrl=<private>)"
}

typealias SpeechVoiceDesignResult = SpeechVoiceCreationResult
typealias SpeechVoiceCloneResult = SpeechVoiceCreationResult

@Serializable
data class SpeechVoiceCreationProgress(
    val taskId: String,
    val selection: SpeechDirectorySelection,
    val updatedAt: String,
    val phase: String = "queued",
    val requestSent: Boolean = false,
    val result: SpeechVoiceCreationResult? = null,
    val errorCode: String? = null,
    val version: Int = 1,
    val operation: String = "design",
    val uploadedFiles: List<SpeechVoiceUploadedFile> = emptyList(),
    val requestCount: Int = 0,
)

typealias SpeechVoiceDesignProgress = SpeechVoiceCreationProgress
typealias SpeechVoiceCloneProgress = SpeechVoiceCreationProgress

@Serializable
data class SpeechVoiceUploadedFile(val role: String, val fileId: String)

@Serializable
data class SpeechVoiceCreationUsage(
    val taskId: String,
    val presetId: String,
    val provider: String,
    val inputCharacters: Long,
    val startedAt: String,
    val status: String = "prepared",
    val requestSent: Boolean = false,
    val endedAt: String? = null,
    val sourceNamespace: String? = null,
    val version: Int = 1,
    val _kind: String = "speech_voice_design",
)

typealias SpeechVoiceDesignUsage = SpeechVoiceCreationUsage
typealias SpeechVoiceCloneUsage = SpeechVoiceCreationUsage
