package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SpeechPreprocessRequest(val text: String, val presetId: String? = null,
    val chatId: String? = null, val assistantId: String? = null, val llmPresetId: String? = null,
    val llmModel: String? = null, val speechModel: String? = null, val targetLanguage: String? = null,
    val injectEmotionTags: Boolean? = null, val maxOutputTokens: Int = 16_384) {
    override fun toString() = "SpeechPreprocessRequest(text=<private>, configuration=<private>)"
}

@Serializable
data class SpeechPreprocessSelection(val presetId: String, val assistantId: String?,
    val sourceNamespace: String?, val speechProvider: String, val speechModel: String,
    val llmPresetId: String, val llmModel: String, val protocol: LlmProtocol,
    val credentialId: String, val hasCredential: Boolean, val targetLanguage: String?,
    val injectEmotionTags: Boolean, val maxOutputTokens: Int)

@Serializable
data class SpeechPreprocessResult(val requestId: String, val processedText: String,
    val selection: SpeechPreprocessSelection, val usage: UsageTokens, val cost: CostSnapshot,
    val warnings: List<String> = emptyList(), val version: Int = 1) {
    override fun toString() = "SpeechPreprocessResult(requestId=$requestId, text=<private>)"
}

/** Persisted selection is evidence about a new request, never permission to replay it. */
object SpeechPreprocessMetadata {
    fun validRequestId(value: String?) = value?.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) == true
    fun validate(selection: SpeechPreprocessSelection) {
        listOfNotNull(selection.presetId, selection.assistantId, selection.sourceNamespace, selection.llmPresetId, selection.credentialId).forEach {
            SpeechText.validate(it, 1024)
            if (it.isBlank() || it.any(Char::isISOControl)) throw StError.Validation("invalid preprocessing selection identity")
        }
        listOf(selection.llmModel, selection.speechModel).forEach {
            SpeechText.validate(it, 2048)
            if (it.isBlank() || it.length > 512 || it.any(Char::isISOControl)) throw StError.Validation("invalid preprocessing selection model")
        }
        selection.targetLanguage?.let {
            SpeechText.validate(it, 256)
            if (it.isBlank() || it.any(Char::isISOControl)) throw StError.Validation("invalid preprocessing language")
        }
        if (selection.speechProvider !in SpeechProviders.supported || selection.maxOutputTokens !in 256..32768 ||
            selection.injectEmotionTags && (selection.speechProvider != "minimax" || selection.speechModel !in setOf("speech-2.8-hd", "speech-2.8-turbo")))
            throw StError.Validation("invalid preprocessing capability or output limit")
    }
}
