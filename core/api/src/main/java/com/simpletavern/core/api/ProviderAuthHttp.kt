package com.simpletavern.core.api

import com.simpletavern.core.data.NativeJsonInput
import com.simpletavern.core.llm.NoHttpReplayInterceptor
import com.simpletavern.core.model.StError
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.*

/** Selected provider/MCP authentication endpoints; not a public arbitrary-URL request API. */
internal class ProviderAuthHttp {
    class Reply(val status: Int, val body: JsonObject?, val resourceMetadata: String? = null, val scope: String? = null) {
        fun requireObject(): JsonObject {
            if (status !in 200..299) throw StError.Provider("provider authentication HTTP request failed",
                mapOf("httpStatus" to status.toString()))
            return body ?: throw StError.Provider("invalid provider authentication response")
        }
        override fun toString() = "ProviderAuthReply(status=$status, body=<redacted>)"
    }
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).addNetworkInterceptor(NoHttpReplayInterceptor())
        .callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun request(url: String, body: RequestBody? = null, headers: Map<String, String> = emptyMap(),
                        readBody: Boolean = true): Reply {
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .apply { headers.forEach { (name, value) -> header(name, value) }; if (body != null) post(body) }.build()
        val response = suspendCancellableCoroutine<Response> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(StError.Provider("provider authentication network request failed"))
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }
        return try {
            val challenge = response.challenges().firstOrNull { it.scheme.equals("Bearer", ignoreCase = true) }
            val metadata = challenge?.authParams?.get("resource_metadata")
            val scope = challenge?.authParams?.get("scope")
            // A successful GET to an MCP endpoint can be a live SSE stream. Only inspect its headers.
            if (!readBody) return Reply(response.code, null, metadata, scope)
            withContext(Dispatchers.IO) {
                coroutineContext.ensureActive()
                try {
                    val source = response.body?.source() ?: throw StError.Provider("empty provider authentication response")
                    if (source.request(65_537)) throw StError.Provider("provider authentication response exceeds budget")
                    val bytes = source.readByteArray()
                    val parsed = try { NativeJsonInput.parse(NativeJsonInput.utf8(bytes)) as? JsonObject }
                        catch (_: StError) { null }
                    Reply(response.code, parsed, metadata, scope)
                } catch (_: IOException) { throw StError.Provider("provider authentication response read failed") }
            }
        } finally { response.close() }
    }
}
