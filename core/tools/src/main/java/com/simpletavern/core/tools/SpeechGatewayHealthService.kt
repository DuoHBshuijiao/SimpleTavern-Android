package com.simpletavern.core.tools

import android.security.NetworkSecurityPolicy
import com.simpletavern.core.llm.NoHttpReplayInterceptor
import com.simpletavern.core.model.*
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class SpeechGatewayHealthSession internal constructor(private val run: suspend () -> SpeechGatewayHealth) {
    suspend fun resolve() = run()
    override fun toString() = "SpeechGatewayHealthSession(configuration=<private>, credentials=<redacted>)"
}

/** Explicit caller-owned GETs only; no synthesis, process launch or persistent health state. */
class SpeechGatewayHealthService(private val speech: SpeechService) {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).addNetworkInterceptor(NoHttpReplayInterceptor())
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()

    fun prepare(selection: SpeechDirectorySelection, secret: String?, cloneBaseUrl: String?,
        request: SpeechGatewayHealthRequest): SpeechGatewayHealthSession {
        if (selection.provider !in SpeechProviders.local) throw StError.Unsupported("health query requires a local TTS gateway")
        if (secret != null && (secret.length > 4096 || secret.any { it.code !in 33..126 }))
            throw StError.Validation("invalid local gateway credential")
        val primary = healthUrl(speech.endpoint(selection.provider, selection.baseUrl).toHttpUrl())
        val cloneConfigured = selection.provider == "qwen3_local" && !cloneBaseUrl.isNullOrBlank()
        val targets = buildList {
            add("synthesis" to primary)
            if (cloneConfigured && request.includeVoiceCloneGateway)
                add("voice_clone" to healthUrl(speech.endpoint(selection.provider, cloneBaseUrl).toHttpUrl()))
        }
        return SpeechGatewayHealthSession {
            val checks = targets.map { (role, url) -> check(role, url, secret) }
            SpeechGatewayHealth(selection, checks, Instant.now().toString(), checks.all { it.state == "reachable" },
                cloneConfigured, buildList {
                    add("gateway_health_not_synthesis_availability_proof")
                    if (selection.provider == "qwen3_local" && !cloneConfigured) add("voice_clone_gateway_not_configured")
                    if (cloneConfigured && !request.includeVoiceCloneGateway) add("voice_clone_gateway_not_checked")
                })
        }
    }

    private fun healthUrl(base: HttpUrl): HttpUrl {
        var path = base.encodedPath.trimEnd('/')
        val synthesisSuffix = listOf("/v1/tts/custom_voice", "/v1/tts/voice_clone", "/api/v1/tts", "/v1/tts")
            .firstOrNull { path.endsWith(it) }
        if (synthesisSuffix != null) path = path.removeSuffix(synthesisSuffix)
        else path = path.removeSuffix("/api/v1").removeSuffix("/v1")
        return base.newBuilder().encodedPath("$path/health").build()
    }

    private suspend fun check(role: String, url: HttpUrl, secret: String?): SpeechGatewayHealthCheck = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (url.scheme == "http" && !NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(url.host))
            return@withContext SpeechGatewayHealthCheck(role, "cleartext_blocked", errorCode = "tts_local_health_cleartext_blocked")
        coroutineScope {
            val request = Request.Builder().url(url).get().apply {
                if (!secret.isNullOrBlank()) header("Authorization", "Bearer $secret")
            }.build()
            val call = http.newCall(request)
            val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                currentCoroutineContext().ensureActive()
                call.execute().use { response ->
                    currentCoroutineContext().ensureActive()
                    // Match the desktop gateway contract; do not return its raw body or diagnostic paths.
                    if (response.code == 200) SpeechGatewayHealthCheck(role, "reachable", 200)
                    else SpeechGatewayHealthCheck(role, "http_error", response.code, "tts_local_health_http_error")
                }
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                if (error is java.io.InterruptedIOException)
                    SpeechGatewayHealthCheck(role, "timeout", errorCode = "tts_local_health_timeout")
                else SpeechGatewayHealthCheck(role, "network_error", errorCode = "tts_local_health_unreachable")
            } finally { watcher.cancel() }
        }
    }
}
