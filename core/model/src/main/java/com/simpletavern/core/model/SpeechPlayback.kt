package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SystemSpeechRequest(val text: String, val speechRate: Double = 1.0, val pitch: Double = 1.0,
    val language: String? = null, val chatId: String? = null, val assistantId: String? = null) {
    override fun toString() = "SystemSpeechRequest(text=<private>)"
}

@Serializable
data class PcmPlaybackFormat(val sampleRate: Int, val channels: Int = 1)

@Serializable
enum class SpeechPlaylistSource { CACHED, ATTACHMENT }

/** Managed IDs only; a playlist never contains a path, URL or synthesis request. */
@Serializable
data class SpeechPlaylistItem(val source: SpeechPlaylistSource, val id: String, val pcmFormat: PcmPlaybackFormat? = null)

@Serializable
data class AudioPlaybackState(val ready: Boolean = false, val paused: Boolean = false,
    val canPause: Boolean = false, val canSeek: Boolean = false,
    val positionMs: Long? = null, val durationMs: Long? = null)

@Serializable
data class SpeechPlaybackProgress(val taskId: String, val mode: String, val updatedAt: String,
    val phase: String = "queued", val assetId: String? = null, val synthesisTaskId: String? = null,
    val assistantId: String? = null, val sourceNamespace: String? = null, val errorCode: String? = null,
    val playbackSpeed: Double = 1.0, val segmentCount: Int = 1, val segmentIndex: Int = 0,
    val assetIds: List<String> = emptyList(), val synthesisTaskIds: List<String> = emptyList(),
    val attachmentId: String? = null, val playback: AudioPlaybackState? = null,
    val playlist: List<SpeechPlaylistItem> = emptyList(), val completedPlaylistIndices: Set<Int> = emptySet(),
    val preprocessingRequestId: String? = null, val voiceCreationTaskId: String? = null,
    val downloadedBytes: Long = 0)
