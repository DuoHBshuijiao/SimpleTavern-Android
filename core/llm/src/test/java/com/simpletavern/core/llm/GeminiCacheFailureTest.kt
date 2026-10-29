package com.simpletavern.core.llm

import com.simpletavern.core.model.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class GeminiCacheFailureTest {
    private val provider = ProviderConfig("google", LlmProtocol.GEMINI_GENERATE_CONTENT,
        "https://generativelanguage.googleapis.com", "test-key", "gemini-2.5-flash")
    private fun reply(request: Request, code: Int, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun deleteOfAlreadyMissingCacheSucceedsWithoutRetry() = runBlocking {
        var calls = 0
        var checkpoints = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals("DELETE", chain.request().method)
            reply(chain.request(), 404, "{}")
        }.build()
        GeminiCacheClient(http).delete(provider, "cachedContents/cache-1") { checkpoints++ }
        assertEquals(1, calls)
        assertEquals(1, checkpoints)
    }

    @Test fun generation404InvalidatesBindingAndClosesLeaseWithoutReplayingGeneration() = runBlocking {
        var generationCalls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath.endsWith("cachedContents")) {
                reply(chain.request(), 200, """{"name":"cachedContents/cache-1","model":"models/gemini-2.5-flash","expireTime":"${Instant.now().plusSeconds(3600)}"}""")
            } else {
                generationCalls++
                reply(chain.request(), 404, "{}")
            }
        }.build()
        val binding = GeminiCacheClient(http).create(provider, "system", null, 3600, beforeExecute = {})
        val owner = GeminiCacheOwner("chat", null, null, provider.providerId, provider.model, provider.baseUrl, "x-goog-api-key", null)
        var invalidated = false
        var released = false
        val client = LlmClient(http, prepareGeminiCache = { active, _, _, _ ->
            GeminiCacheUse(active.copy(cachedContext = binding, cachePlan = null), invalidate = { invalidated = true }) { released = true }
        })
        val request = LlmRequest("request", provider.protocol, provider.model,
            listOf(ContextMessage(ChatRole.system, "system"), ContextMessage(ChatRole.user, "hello")))
        try {
            client.stream(provider.copy(cachePlan = GeminiCachePlan(owner)), request).toList()
            fail("404 should terminate this generation")
        } catch (error: StError.Provider) {
            assertEquals("404", error.details["httpStatus"])
        }
        assertTrue(invalidated)
        assertTrue(released)
        assertEquals(1, generationCalls)
    }
}
