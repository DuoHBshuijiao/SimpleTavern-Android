package com.simpletavern.core.api

import com.simpletavern.core.data.NativeJsonInput
import com.simpletavern.core.llm.NoHttpReplayInterceptor
import com.simpletavern.core.model.StError
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/** Public metadata only. No provider login/key, model call, retry or implicit price installation. */
internal class ModelCatalogRefreshClient {
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .addNetworkInterceptor(NoHttpReplayInterceptor()).connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()
    private val refresh = Mutex()

    suspend fun update(): ModelCatalogPage = withContext(Dispatchers.IO) {
        refresh.withLock {
            val normalized = coroutineScope {
                val models = async { read("https://models.dev/api.json") }
                val router = async { read("https://openrouter.ai/api/v1/models") }
                ModelCatalogSources.normalize(models.await(), router.await(), Instant.now().toString())
            }
            currentCoroutineContext().ensureActive()
            // One small local publication after both downloads/decodes; failed fetches keep the old snapshot.
            withContext(NonCancellable) { ModelCatalog.install(normalized) }
        }
    }

    private suspend fun read(url: String): JsonObject = coroutineScope {
        val call = http.newCall(Request.Builder().url(url).get().header("Accept", "application/json")
            .header("User-Agent", "SimpleTavern-Android-catalog").build())
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                if (!response.isSuccessful) throw StError.Provider("catalog HTTP ${response.code}")
                val body = response.body ?: throw StError.Provider("empty catalog response")
                if (body.contentLength() > ModelCatalog.MAX_BYTES) throw StError.Provider("catalog response exceeds budget")
                val output = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(65_536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > ModelCatalog.MAX_BYTES - output.size()) throw StError.Provider("catalog response exceeds budget")
                        output.write(buffer, 0, count)
                    }
                }
                Json.parseToJsonElement(NativeJsonInput.utf8(output.toByteArray())) as? JsonObject
                    ?: throw StError.Provider("invalid catalog response")
            }
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            throw StError.Provider("catalog request failed")
        } catch (_: SerializationException) { throw StError.Provider("invalid catalog response") }
        finally { watcher.cancel() }
    }
}
