package com.simpletavern.core.llm

import com.simpletavern.core.model.StError
import java.net.URI
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** DeepSeek tools use the same selected API key, executed by the shared chat/assistant tool loop. */
class DeepSeekWebClient(private val provider: ProviderConfig, search: Boolean, fetch: Boolean) {
    val definitions = buildJsonArray {
        if (search) add(definition("deepseek_web_search", "Search the web using DeepSeek. Cite source URLs.", "query", "Search query, 1 to 128 Unicode characters"))
        if (fetch) add(definition("deepseek_web_fetch", "Read a web page using DeepSeek and cite its URL.", "url", "An HTTP or HTTPS page URL"))
    }
    private val names = definitions.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
    private var calls = 0
    private var resultBytes = 0
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).addNetworkInterceptor(NoHttpReplayInterceptor())
        .callTimeout(60, TimeUnit.SECONDS).build()
    fun handles(name: String) = name in names
    suspend fun execute(name: String, arguments: String): String {
        if (!handles(name)) throw StError.Validation("undeclared native DeepSeek tool")
        if (++calls > 16) throw StError.Provider("DeepSeek web tool limit reached")
        if (URI(provider.baseUrl).host != "api.deepseek.com" || URI(provider.baseUrl).scheme != "https")
            throw StError.Validation("DeepSeek tools require the official API root")
        val args = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrElse { throw StError.Validation("invalid DeepSeek tool arguments") }
        val search = name == "deepseek_web_search"
        val field = if (search) "query" else "url"
        val value = (args[field] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw StError.Validation("missing DeepSeek tool input")
        if (search) {
            if (value.isBlank() || value.codePointCount(0, value.length) !in 1..128) throw StError.Validation("DeepSeek query requires 1 to 128 Unicode characters")
        } else {
            val uri = runCatching { URI(value) }.getOrNull()
            if (value.length > 8192 || uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank() || uri?.rawUserInfo != null)
                throw StError.Validation("invalid DeepSeek fetch URL")
        }
        val payload = buildJsonObject { put(field, value); if (search) { put("count", 5); put("need_content", false) } }
        val request = Request.Builder().url("https://api.deepseek.com/v1/tools/" + if (search) "search" else "fetch")
            .header("Authorization", "Bearer " + provider.apiKey).post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val response = suspendCancellableCoroutine<Response> { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(StError.Provider("DeepSeek web request failed")) }
                override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
            })
        }
        val obj = try { withContext(Dispatchers.IO) {
            if (!response.isSuccessful) throw StError.Provider("DeepSeek web request failed", mapOf("httpStatus" to response.code.toString()))
            val source = response.body?.source() ?: throw StError.Provider("empty DeepSeek web response")
            if (source.request(2_097_153)) throw StError.Provider("DeepSeek web response exceeds budget")
            Json.parseToJsonElement(source.readUtf8()).jsonObject
        } } finally { response.close() }
        var truncated = false
        fun bound(value: JsonElement, depth: Int = 0): JsonElement {
            if (depth > 24) throw StError.Provider("DeepSeek result exceeds structure budget")
            return when (value) {
                is JsonPrimitive -> if (value.isString && value.content.length > 24_000) { truncated = true; JsonPrimitive(value.content.take(24_000)) } else value
                is JsonArray -> JsonArray(value.take(50).map { bound(it, depth + 1) })
                is JsonObject -> JsonObject(value.mapValues { bound(it.value, depth + 1) })
            }
        }
        val bounded = bound(obj).jsonObject
        val result = JsonObject(bounded + ("truncated" to JsonPrimitive(truncated))).toString()
        resultBytes += result.toByteArray(Charsets.UTF_8).size
        if (resultBytes > 524_288) throw StError.Provider("DeepSeek web result budget reached")
        if (provider.containsCredential(result)) throw StError.Provider("DeepSeek web response contains credential material")
        return result
    }
    private fun definition(name: String, description: String, field: String, help: String) = buildJsonObject {
        put("type", "function"); put("function", buildJsonObject {
            put("name", name); put("description", description); put("parameters", buildJsonObject {
                put("type", "object"); put("properties", buildJsonObject { put(field, buildJsonObject { put("type", "string"); put("description", help) }) })
                put("required", JsonArray(listOf(JsonPrimitive(field)))); put("additionalProperties", false)
            })
        })
    }
}
