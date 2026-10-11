package com.simpletavern.core.api

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.simpletavern.core.model.*
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** System speech or managed audio bytes/files; no public URLs or user-supplied playback paths. */
internal class DeviceAudioOutput(private val context: Context) {
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private class FocusLost : CancellationException("audio focus lost")

    private suspend fun focused(play: suspend () -> Unit) {
        try {
            coroutineScope {
                val manager = context.getSystemService(AudioManager::class.java)
                    ?: throw StError.Speech("audio_unavailable", "device audio service unavailable")
                val owner = currentCoroutineContext().job
                val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener({ change ->
                        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) owner.cancel(FocusLost())
                    }, Handler(Looper.getMainLooper())).build()
                if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    throw StError.Speech("audio_focus_denied", "device did not grant audio focus")
                try { play() } finally { manager.abandonAudioFocusRequest(focus) }
            }
        } catch (error: CancellationException) {
            if (error is FocusLost || error.cause is FocusLost)
                throw StError.Speech("audio_focus_lost", "playback stopped after audio focus loss; no automatic resume")
            throw error
        }
    }

    suspend fun speak(request: SystemSpeechRequest) = focused {
        withContext(Dispatchers.Main) {
            val ready = CompletableDeferred<Int>()
            val engine = TextToSpeech(context) { ready.complete(it) }
            try {
                val initialized = withTimeoutOrNull(15_000) { ready.await() }
                    ?: throw StError.Speech("system_tts_init_timeout", "system TTS initialization timed out")
                if (initialized != TextToSpeech.SUCCESS)
                    throw StError.Speech("system_tts_unavailable", "system TTS engine failed to initialize")
                if (engine.setSpeechRate(request.speechRate.toFloat()) == TextToSpeech.ERROR ||
                    engine.setPitch(request.pitch.toFloat()) == TextToSpeech.ERROR)
                    throw StError.Speech("system_tts_controls", "system TTS rejected rate or pitch")
                request.language?.let { tag ->
                    val language = Locale.forLanguageTag(tag)
                    if (language.language.isBlank() || engine.setLanguage(language) < 0)
                        throw StError.Speech("system_tts_language", "system TTS language is unavailable; no automatic language download")
                }
                engine.setAudioAttributes(attributes)
                val completed = CompletableDeferred<Unit>()
                val chunks = chunks(request.text, TextToSpeech.getMaxSpeechInputLength())
                val last = "part-${chunks.lastIndex}"
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { if (utteranceId == last) completed.complete(Unit) }
                    @Suppress("DEPRECATION")
                    override fun onError(utteranceId: String?) { completed.completeExceptionally(StError.Speech("system_tts_error", "system TTS failed")) }
                    override fun onError(utteranceId: String?, errorCode: Int) { completed.completeExceptionally(StError.Speech("system_tts_error", "system TTS error=$errorCode")) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { completed.completeExceptionally(StError.Speech("system_tts_stopped", "system TTS stopped before completion")) }
                })
                chunks.forEachIndexed { index, text ->
                    currentCoroutineContext().ensureActive()
                    if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, "part-$index") == TextToSpeech.ERROR)
                        throw StError.Speech("system_tts_enqueue", "system TTS rejected speech")
                }
                completed.await()
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { try { engine.stop() } finally { engine.shutdown() } }
            }
        }
    }

    private fun chunks(text: String, max: Int): List<String> = buildList {
        var start = 0
        while (start < text.length) {
            var end = minOf(start + max, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            add(text.substring(start, end)); start = end
        }
    }

    suspend fun play(audio: SpeechAudio, pcmFormat: PcmPlaybackFormat?, speed: Double, control: AudioPlaybackControl) = focused {
        if (audio.format == "pcm") pcm(audio, pcmFormat ?: throw StError.Speech("pcm_layout_required", "raw PCM layout is unverified; provide its actual 16-bit little-endian rate/channels"), speed, control) else encoded(audio.bytes, speed, control)
    }

    suspend fun playFile(file: File, format: String, pcmFormat: PcmPlaybackFormat?, speed: Double, control: AudioPlaybackControl,
                         onReady: suspend () -> Unit = {}) = withContext(Dispatchers.IO) {
        focused {
            if (format == "pcm") pcmFile(file, pcmFormat ?: throw StError.Speech("pcm_layout_required", "raw PCM requires its actual layout"), speed, control, onReady)
            else RandomAccessFile(file, "r").use { reader ->
                val source = object : MediaDataSource() {
                    @Synchronized override fun getSize() = reader.length()
                    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                        if (size == 0) return 0
                        if (position >= reader.length()) return -1
                        reader.seek(position)
                        return reader.read(buffer, offset, size)
                    }
                    @Synchronized override fun close() = reader.close()
                }
                encodedSource(source, speed, control, true, onReady)
            }
        }
    }

    private suspend fun pcmFile(file: File, format: PcmPlaybackFormat, speed: Double, control: AudioPlaybackControl,
                                onReady: suspend () -> Unit) = withContext(Dispatchers.IO) {
        RandomAccessFile(file, "r").use { reader ->
            pcmSeekable(reader.length(), format, speed, control, onReady) { offset, buffer, count ->
                reader.seek(offset); reader.readFully(buffer, 0, count); count
            }
        }
    }

    suspend fun playStream(chunks: ReceiveChannel<SpeechAudioChunk>, pcmFormat: PcmPlaybackFormat?, speed: Double, control: AudioPlaybackControl,
        onStarted: suspend () -> Unit) {
        val first = chunks.receiveCatching().getOrThrow()
        focused {
            if (first.format == "pcm") {
                val sampleRate = first.sampleRate
                val channels = first.channels
                val format = pcmFormat ?: if (sampleRate != null && channels != null)
                    PcmPlaybackFormat(sampleRate, channels) else throw StError.Speech("pcm_layout_required", "stream PCM layout must be selected")
                if ((sampleRate != null && sampleRate != format.sampleRate) ||
                    (channels != null && channels != format.channels))
                    throw StError.Speech("pcm_layout_mismatch", "selected PCM layout differs from the audio stream")
                pcmStream(first, chunks, format, speed, control, onStarted)
            } else coroutineScope {
                val source = ProgressiveSpeechSource()
                source.append(first.bytes)
                val feeder = launch(Dispatchers.IO) {
                    var failure: Throwable? = null
                    try { for (chunk in chunks) source.append(chunk.bytes) }
                    catch (error: Throwable) { failure = error; throw error }
                    finally { source.finish(failure) }
                }
                try { encodedSource(source, speed, control, false, onStarted); feeder.join() }
                finally { source.close(); feeder.cancel() }
            }
        }
    }

    private suspend fun encoded(bytes: ByteArray, speed: Double, control: AudioPlaybackControl) = withContext(Dispatchers.Main) {
        val source = object : MediaDataSource() {
            override fun getSize() = bytes.size.toLong()
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (size == 0) return 0
                if (position >= bytes.size) return -1
                val count = minOf(size, bytes.size - position.toInt())
                bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                return count
            }
            override fun close() = Unit
        }
        encodedSource(source, speed, control, true) {}
    }

    private suspend fun encodedSource(source: MediaDataSource, speed: Double, control: AudioPlaybackControl, seekable: Boolean,
        onStarted: suspend () -> Unit) = withContext(Dispatchers.Main) {
        val player = MediaPlayer()
        val prepared = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        try {
            player.setAudioAttributes(attributes)
            player.setOnPreparedListener { prepared.complete(Unit) }
            player.setOnCompletionListener { completed.complete(Unit) }
            player.setOnErrorListener { _, what, extra ->
                val failure = StError.Speech("audio_decode_failed", "device player error=$what/$extra")
                prepared.completeExceptionally(failure); completed.completeExceptionally(failure); true
            }
            player.setDataSource(source)
            player.prepareAsync()
            if (withTimeoutOrNull(15_000) { prepared.await(); true } != true)
                throw StError.Speech("audio_prepare_timeout", "device player preparation timed out")
            currentCoroutineContext().ensureActive()
            // Start only after the control applies the selected item position and pause intent.
            if (speed != 1.0) { player.playbackParams = playbackParams(speed); player.pause() }
            var paused = true
            val backend = object : AudioPlaybackControl.Backend {
                override suspend fun state() = withContext(Dispatchers.Main) {
                    AudioPlaybackState(true, paused, !completed.isCompleted, seekable && !completed.isCompleted,
                        player.currentPosition.toLong(), player.duration.toLong().takeIf { it >= 0 })
                }
                override suspend fun pause(pausedValue: Boolean) = withContext(Dispatchers.Main) {
                    if (completed.isCompleted) throw StError.Conflict("audio output has ended")
                    if (pausedValue != paused) { if (pausedValue) player.pause() else player.start(); paused = pausedValue }
                }
                override suspend fun seek(positionMs: Long) = withContext(Dispatchers.Main) {
                    if (!seekable) throw StError.Unsupported("live synthesized audio cannot seek; play the completed cached asset")
                    if (completed.isCompleted) throw StError.Conflict("audio output has ended")
                    val sought = CompletableDeferred<Unit>()
                    player.setOnSeekCompleteListener { sought.complete(Unit) }
                    try {
                        player.seekTo(positionMs.coerceAtMost(player.duration.toLong().coerceAtLeast(0)), MediaPlayer.SEEK_CLOSEST)
                        withTimeout(10_000) { sought.await() }
                    } finally { player.setOnSeekCompleteListener(null) }
                }
            }
            control.use(backend) { onStarted(); completed.await() }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { try { source.close() } finally { player.release() } }
        }
    }

    private fun playbackParams(speed: Double) = PlaybackParams().allowDefaults().setPitch(1f).setSpeed(speed.toFloat())
        .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL)

    private suspend fun pcmStream(first: SpeechAudioChunk, chunks: ReceiveChannel<SpeechAudioChunk>, format: PcmPlaybackFormat,
        speed: Double, control: AudioPlaybackControl, onStarted: suspend () -> Unit) = withContext(Dispatchers.IO) {
        val mask = when (format.channels) { 1 -> AudioFormat.CHANNEL_OUT_MONO; 2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> throw StError.Speech("pcm_channels", "PCM playback supports mono or stereo") }
        val minimum = AudioTrack.getMinBufferSize(format.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw StError.Speech("pcm_format", "device does not support the PCM format")
        val track = AudioTrack.Builder().setAudioAttributes(attributes).setTransferMode(AudioTrack.MODE_STREAM)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(format.sampleRate).setChannelMask(mask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(minimum, 8192)).build()
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) throw StError.Speech("pcm_player", "device PCM player failed to initialize")
            if (speed != 1.0) track.playbackParams = playbackParams(speed)
            onStarted(); track.play()
            var carry = ByteArray(0)
            var writtenBytes = 0L
            val frameBytes = 2 * format.channels
            val device = Mutex()
            var paused = false
            val backend = object : AudioPlaybackControl.Backend {
                override suspend fun state() = device.withLock {
                    AudioPlaybackState(true, paused, true, false, (track.playbackHeadPosition.toLong() and 0xffffffffL) * 1000 / format.sampleRate)
                }
                override suspend fun pause(pausedValue: Boolean) { device.withLock {
                    if (pausedValue != paused) { if (pausedValue) track.pause() else track.play(); paused = pausedValue }
                } }
                override suspend fun seek(positionMs: Long) { throw StError.Unsupported("live PCM cannot seek; play the completed cached asset") }
            }
            suspend fun write(chunk: SpeechAudioChunk) {
                val bytes = if (carry.isEmpty()) chunk.bytes else carry + chunk.bytes
                val aligned = bytes.size - bytes.size % frameBytes
                var at = 0
                while (at < aligned) {
                    currentCoroutineContext().ensureActive()
                    val written = device.withLock { if (paused) 0 else track.write(bytes, at, minOf(8192, aligned - at), AudioTrack.WRITE_NON_BLOCKING) }
                    if (written < 0) throw StError.Speech("pcm_write", "device PCM player rejected audio")
                    if (written == 0) { delay(10); continue }
                    at += written; writtenBytes += written
                }
                carry = bytes.copyOfRange(aligned, bytes.size)
            }
            control.use(backend) {
                write(first)
                for (chunk in chunks) write(chunk)
                if (carry.isNotEmpty()) throw StError.Speech("pcm_shape", "stream ended with an incomplete PCM frame")
                while ((track.playbackHeadPosition.toLong() and 0xffffffffL) < writtenBytes / frameBytes) delay(25)
            }
        } finally { try { track.stop() } finally { track.release() } }
    }

    private suspend fun pcm(audio: SpeechAudio, format: PcmPlaybackFormat, speed: Double, control: AudioPlaybackControl) = withContext(Dispatchers.IO) {
        if (audio.sampleRate != null && audio.sampleRate != format.sampleRate)
            throw StError.Speech("pcm_sample_rate", "PCM sample rate differs from reported audio")
        pcmSeekable(audio.bytes.size.toLong(), format, speed, control) { offset, buffer, count ->
            audio.bytes.copyInto(buffer, 0, offset.toInt(), offset.toInt() + count); count
        }
    }

    /** Bounded seekable PCM reader shared by cached bytes and managed files. */
    private suspend fun pcmSeekable(size: Long, format: PcmPlaybackFormat, speed: Double, control: AudioPlaybackControl,
        onReady: suspend () -> Unit = {},
        readAt: (Long, ByteArray, Int) -> Int) = withContext(Dispatchers.IO) {
        val rate = format.sampleRate
        val mask = when (format.channels) { 1 -> AudioFormat.CHANNEL_OUT_MONO; 2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> throw StError.Speech("pcm_channels", "PCM playback supports mono or stereo") }
        if (rate <= 0) throw StError.Speech("pcm_sample_rate", "PCM sample rate is invalid")
        val frameBytes = 2 * format.channels
        if (size % frameBytes != 0L) throw StError.Speech("pcm_shape", "16-bit PCM has an incomplete frame")
        val minimum = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw StError.Speech("pcm_format", "device does not support the PCM format")
        val track = AudioTrack.Builder().setAudioAttributes(attributes).setTransferMode(AudioTrack.MODE_STREAM)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(minimum, 8192)).build()
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) throw StError.Speech("pcm_player", "device PCM player failed to initialize")
            if (speed != 1.0) track.playbackParams = playbackParams(speed)
            track.play()
            val device = Mutex()
            val buffer = ByteArray(65536)
            var offset = 0L
            var at = 0
            var count = 0
            var baseFrame = 0L
            var writtenBytes = 0L
            var paused = false
            val frames = size / frameBytes
            val duration = frames * 1000 / rate
            val backend = object : AudioPlaybackControl.Backend {
                override suspend fun state() = device.withLock {
                    AudioPlaybackState(true, paused, true, true,
                        minOf(frames, baseFrame + (track.playbackHeadPosition.toLong() and 0xffffffffL)) * 1000 / rate, duration)
                }
                override suspend fun pause(pausedValue: Boolean) { device.withLock {
                    if (pausedValue != paused) { if (pausedValue) track.pause() else track.play(); paused = pausedValue }
                } }
                override suspend fun seek(positionMs: Long) { device.withLock {
                    track.pause(); track.flush()
                    baseFrame = minOf(frames, positionMs.coerceAtMost(duration) * rate / 1000)
                    offset = baseFrame * frameBytes; at = 0; count = 0; writtenBytes = 0
                    if (!paused) track.play()
                } }
            }
            control.use(backend) {
                onReady()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    var wrote = 0
                    val done = device.withLock {
                        if (paused) false else {
                            if (at == count && offset < size) {
                                count = readAt(offset, buffer, minOf(buffer.size.toLong(), size - offset).toInt()); at = 0
                                if (count <= 0 || count % frameBytes != 0) throw StError.Speech("pcm_read", "PCM source ended or changed unexpectedly")
                                offset += count
                            }
                            if (at < count) {
                                val written = track.write(buffer, at, minOf(8192, count - at), AudioTrack.WRITE_NON_BLOCKING)
                                if (written < 0) throw StError.Speech("pcm_write", "device PCM player rejected audio")
                                wrote = written
                                at += written; writtenBytes += written
                            }
                            offset == size && at == count && (track.playbackHeadPosition.toLong() and 0xffffffffL) >= writtenBytes / frameBytes
                        }
                    }
                    if (done) break
                    if (wrote == 0) delay(10)
                }
            }
        } finally { try { track.stop() } finally { track.release() } }
    }
}
