package com.simpletavern.core.api

import com.simpletavern.core.model.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One live device output owned by an existing playback task, never a replayable player. */
internal class AudioPlaybackControl {
    interface Backend {
        suspend fun state(): AudioPlaybackState
        suspend fun pause(paused: Boolean)
        suspend fun seek(positionMs: Long)
    }
    private val mutex = Mutex()
    private var backend: Backend? = null
    private var last = AudioPlaybackState()
    private var active = false
    private var pauseRequested = false
    private var nextPositionMs: Long? = null

    private suspend fun currentState() = backend?.state() ?: last.copy(paused = pauseRequested, canPause = active)
    suspend fun state() = mutex.withLock { currentState() }
    suspend fun begin() { mutex.withLock { active = true } }
    suspend fun prepareItem(positionMs: Long) { mutex.withLock {
        nextPositionMs = positionMs.takeIf { it > 0 }
        last = AudioPlaybackState(paused = pauseRequested, canPause = active)
    } }
    suspend fun finish() { mutex.withLock { active = false; pauseRequested = false; last = last.copy(canPause = false, canSeek = false, paused = false) } }
    suspend fun pause(paused: Boolean): AudioPlaybackState = mutex.withLock {
        if (!active) throw StError.Conflict("playback has ended or is not ready")
        backend?.pause(paused)
        pauseRequested = paused
        currentState()
    }
    suspend fun seek(positionMs: Long): AudioPlaybackState = mutex.withLock {
        if (positionMs < 0) throw StError.Validation("playback position must be nonnegative")
        val live = backend ?: throw StError.Conflict("audio output is not ready or has ended")
        live.seek(positionMs); live.state()
    }
    suspend fun <T> use(live: Backend, play: suspend () -> T): T {
        mutex.withLock {
            nextPositionMs?.let { live.seek(it) }
            nextPositionMs = null
            live.pause(pauseRequested)
            backend = live
        }
        try { return play() }
        finally { withContext(NonCancellable) { mutex.withLock {
            try { last = live.state() } catch (_: Exception) { /* Device may already be in error state. */ }
            finally { backend = null; last = last.copy(ready = false, paused = false, canPause = false, canSeek = false) }
        } } }
    }
}
