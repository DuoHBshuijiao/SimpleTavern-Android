package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SpeechVoiceCloneRequest(
    val sourceAttachmentId: String,
    val voiceId: String,
    val presetId: String? = null,
    val chatId: String? = null,
    val assistantId: String? = null,
    val model: String? = null,
    val previewText: String? = null,
    val referenceText: String? = null,
    val promptAttachmentId: String? = null,
    val promptText: String? = null,
    val languageBoost: String? = null,
    val noiseReduction: Boolean = false,
    val volumeNormalization: Boolean = false,
    val aigcWatermark: Boolean = false,
    val textValidation: String? = null,
    val accuracy: Double? = null,
) {
    override fun toString() =
        "SpeechVoiceCloneRequest(sourceAttachmentId=<private>, voiceId=$voiceId, presetId=$presetId, chatId=$chatId, assistantId=$assistantId, model=$model, previewText=<private>, referenceText=<private>, promptAttachmentId=$promptAttachmentId, promptText=<private>, languageBoost=$languageBoost, noiseReduction=$noiseReduction, volumeNormalization=$volumeNormalization, aigcWatermark=$aigcWatermark, textValidation=<private>, accuracy=$accuracy)"
}
