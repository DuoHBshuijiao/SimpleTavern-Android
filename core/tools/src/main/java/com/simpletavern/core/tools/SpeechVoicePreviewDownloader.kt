package com.simpletavern.core.tools

import com.simpletavern.core.model.AttachmentMimeTypes
import com.simpletavern.core.model.StError
import com.simpletavern.core.llm.NoHttpReplayInterceptor
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Fetches an untrusted remote preview into a caller-owned managed attachment stream. */
class SpeechVoicePreviewDownloader {
    private val http = OkHttpClient.Builder()
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .addNetworkInterceptor(NoHttpReplayInterceptor())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    /** The managed attachment copy in [consume] reads its input to EOF before committing. */
    suspend fun <T> download(
        url: String,
        consume: suspend (filename: String, mimeType: String, input: InputStream) -> T,
    ): T = withContext(Dispatchers.IO) {
        coroutineScope {
            val parsed = if (url.length <= MAX_URL_CHARS) url.toHttpUrlOrNull() else null
            if (parsed == null || parsed.scheme != "https" || parsed.username.isNotEmpty() ||
                parsed.password.isNotEmpty() || parsed.fragment != null
            ) invalidUrl()

            val call = http.newCall(Request.Builder().url(parsed).get().build())
            val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                currentCoroutineContext().ensureActive()
                val response = try {
                    call.execute()
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw transportError(error)
                }
                response.use { res ->
                    currentCoroutineContext().ensureActive()
                    checkResponse(res)
                    val body = res.body ?: invalidResponse()
                    val length = body.contentLength()
                    if (length > MAX_BYTES) tooLarge()
                    val mime = resolveMime(res, parsed.encodedPath)
                    val stream = BoundedPreviewInputStream(body.byteStream(), length)
                    try {
                        consume("$FILENAME.${EXTENSION_BY_MIME.getValue(mime)}", mime, stream)
                    } catch (error: IOException) {
                        currentCoroutineContext().ensureActive()
                        throw transportError(error)
                    }
                }
            } finally {
                watcher.cancel()
            }
        }
    }

    private fun checkResponse(response: Response) {
        if (response.code in 300..399) throw speechError("tts_preview_redirect_refused", "voice preview redirect was refused")
        if (response.code == 404 || response.code == 410)
            throw speechError("tts_preview_url_expired", "voice preview URL is unavailable or expired")
        if (response.code == 401 || response.code == 403)
            throw speechError("tts_auth_failed", "voice preview request was not authorized")
        if (!response.isSuccessful) throw speechError("tts_preview_http_error", "voice preview download failed")
    }

    private fun resolveMime(response: Response, encodedPath: String): String {
        val reported = response.header("Content-Type")
        val normalized = reported?.takeIf { it.isNotBlank() }?.let(AttachmentMimeTypes::normalize)
        if (normalized != null && normalized != "application/octet-stream") {
            if (normalized !in SUPPORTED_MIME_TYPES) invalidMime()
            return normalized
        }
        val extension = encodedPath.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
        return MIME_BY_EXTENSION[extension] ?: invalidMime()
    }

    private inner class BoundedPreviewInputStream(input: InputStream, private val declaredLength: Long) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val value = try {
                `in`.read()
            } catch (error: IOException) {
                throw streamReadError(error, count, declaredLength)
            }
            if (value < 0) {
                verifyEof()
                return -1
            }
            addBytes(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val allowed = minOf(length.toLong(), MAX_BYTES - count + 1).toInt()
            val read = try {
                `in`.read(buffer, offset, allowed)
            } catch (error: IOException) {
                throw streamReadError(error, count, declaredLength)
            }
            if (read < 0) {
                verifyEof()
                return -1
            }
            addBytes(read.toLong())
            return read
        }

        private fun addBytes(bytes: Long) {
            count += bytes
            if (count > MAX_BYTES) tooLarge()
            if (declaredLength >= 0 && count > declaredLength) invalidResponse()
        }

        private fun verifyEof() {
            if (count == 0L) emptyAudio()
            if (declaredLength >= 0 && count != declaredLength) truncatedAudio()
        }
    }

    private fun transportError(error: IOException): StError.Speech = speechError(
        if (error is java.io.InterruptedIOException) "tts_preview_timeout" else "tts_preview_network_error",
        if (error is java.io.InterruptedIOException) "voice preview download timed out" else "voice preview download failed",
    )

    private fun streamReadError(error: IOException, bytesRead: Long, declaredLength: Long): StError.Speech =
        if (error !is java.io.InterruptedIOException && declaredLength >= 0 && bytesRead < declaredLength)
            speechError("tts_preview_truncated", "voice preview download was incomplete")
        else transportError(error)

    private fun invalidUrl(): Nothing = throw speechError("tts_preview_invalid_url", "voice preview URL is invalid")
    private fun invalidMime(): Nothing = throw speechError("tts_preview_invalid_mime", "voice preview format is unsupported")
    private fun tooLarge(): Nothing = throw speechError("tts_preview_too_large", "voice preview exceeds the download limit")
    private fun emptyAudio(): Nothing = throw speechError("tts_preview_empty", "voice preview is empty")
    private fun truncatedAudio(): Nothing = throw speechError("tts_preview_truncated", "voice preview download was incomplete")
    private fun invalidResponse(): Nothing = throw speechError("tts_preview_invalid_response", "voice preview response is invalid")

    private fun speechError(code: String, message: String) = StError.Speech(code, message)

    companion object {
        const val MAX_BYTES = 20_000_000L
        private const val MAX_URL_CHARS = 8192
        private const val FILENAME = "voice-preview"
        private val EXTENSION_BY_MIME = mapOf(
            "audio/wav" to "wav", "audio/mpeg" to "mp3", "audio/aac" to "aac", "audio/flac" to "flac",
            "audio/ogg" to "ogg", "audio/opus" to "opus", "audio/m4a" to "m4a", "audio/webm" to "webm",
        )
        private val SUPPORTED_MIME_TYPES = setOf(
            "audio/wav", "audio/mpeg", "audio/aac", "audio/flac", "audio/ogg", "audio/opus", "audio/m4a", "audio/webm",
        )
        private val MIME_BY_EXTENSION = mapOf(
            "wav" to "audio/wav", "mp3" to "audio/mpeg", "aac" to "audio/aac", "flac" to "audio/flac",
            "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/opus", "m4a" to "audio/m4a",
            "webm" to "audio/webm",
        )
    }
}
