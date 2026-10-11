package com.simpletavern.core.model

import kotlinx.serialization.Serializable

/** Catalog management is independent of synthesis text, voice and the source enable switch. */
@Serializable
data class SpeechDirectoryRequest(val presetId: String? = null, val chatId: String? = null,
    val assistantId: String? = null, val fetchVoices: Boolean = false, val fetchModels: Boolean = false)

@Serializable
data class SpeechDirectorySelection(val presetId: String, val assistantId: String?, val provider: String,
    val baseUrl: String, val credentialId: String, val hasCredential: Boolean, val enabled: Boolean,
    val sourceNamespace: String? = null)

/** Origin is configured, desktop_builtin or remote; none is an automatic selection. */
@Serializable
data class SpeechVoiceInfo(val voiceId: String, val name: String, val voiceType: String,
    val origin: String, val model: String? = null)

@Serializable
data class SpeechModelInfo(val model: String, val name: String, val origin: String,
    val capability: String = "unverified")

/** state: not_requested / completed / unsupported / failed. Only completed has fetchedAt. */
@Serializable
data class SpeechDirectoryFetch(val state: String = "not_requested", val fetchedAt: String? = null,
    val errorCode: String? = null, val warnings: List<String> = emptyList())

@Serializable
data class SpeechDirectory(val selection: SpeechDirectorySelection, val voices: List<SpeechVoiceInfo>,
    val models: List<SpeechModelInfo>, val voiceFetch: SpeechDirectoryFetch = SpeechDirectoryFetch(),
    val modelFetch: SpeechDirectoryFetch = SpeechDirectoryFetch(), val warnings: List<String> = emptyList(),
    val version: Int = 1) {
    override fun toString() = "SpeechDirectory(provider=${selection.provider}, voices=${voices.size}, models=${models.size})"
}
