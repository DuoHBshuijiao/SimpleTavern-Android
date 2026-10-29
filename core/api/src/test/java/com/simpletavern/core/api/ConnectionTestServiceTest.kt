package com.simpletavern.core.api

import com.simpletavern.core.llm.*
import com.simpletavern.core.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ConnectionTestServiceTest {
    private val provider = ProviderConfig("preset", LlmProtocol.OPENAI_COMPATIBLE,
        "https://api.example/v1", "fixture-secret", "custom-model", streaming = false,
        toolsJson = "[]", extra = mapOf("max_tokens" to "99999", "cache_control" to "{}"))
    private fun reply(request: Request, code: Int, body: String) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()
    private val success = """{"choices":[{"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":8,"completion_tokens":1,"total_tokens":9}}"""

    @Test fun testsTheSelectedPresetWithOneBoundedRequestAndReturnsUsage() = runBlocking {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
            val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            assertEquals(JsonPrimitive("custom-model"), body["model"])
            assertEquals(JsonPrimitive(32), body["max_tokens"])
            assertNull(body["tools"]); assertNull(body["cache_control"])
            reply(chain.request(), 200, success)
        }.build()
        val tester = ConnectionTestService({ id -> assertEquals("chosen", id); provider }, LlmClient(http))
        val result = tester.test("chosen")
        assertTrue(result.message, result.success)
        assertEquals(8L, result.usage!!.inputTokens)
        assertEquals(1, calls)
        assertEquals("custom-model", result.model)
    }

    @Test fun failedAuthorizationIsSanitizedAndNeverRetried() = runBlocking {
        var calls = 0
        val client = LlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls++; reply(chain.request(), 401, "fixture-secret private server message")
        }.build())
        val result = ConnectionTestService({ provider }, client).test("preset")
        assertFalse(result.success)
        assertEquals("提供商返回 HTTP 401", result.message)
        assertFalse(result.toString().contains("fixture-secret"))
        assertEquals(1, calls)
    }

    @Test fun preservesTheConfiguredCompletionTokenField() = runBlocking {
        val client = LlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
            val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            assertEquals(JsonPrimitive(32), body["max_completion_tokens"])
            assertNull(body["max_tokens"])
            reply(chain.request(), 200, success)
        }.build())
        val result = ConnectionTestService({ provider.copy(extra = mapOf("max_token_field" to "max_completion_tokens")) }, client).test("preset")
        assertTrue(result.success)
    }

    @Test fun hittingTheSmallOutputLimitStillConfirmsConnectivity() = runBlocking {
        val client = LlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            reply(chain.request(), 200, success.replace("\"stop\"", "\"length\""))
        }.build())
        assertTrue(ConnectionTestService({ provider }, client).test("preset").success)
    }

    @Test fun missingConfigurationReturnsAnActionableFailure() = runBlocking {
        val result = ConnectionTestService({ throw StError.NotFound("untrusted fixture-secret") }).test("missing")
        assertFalse(result.success)
        assertEquals("not_found", result.errorCode)
        assertFalse(result.toString().contains("fixture-secret"))
    }

    @Test fun localTimeoutIsReportedAndCallerCancellationPropagates() = runBlocking {
        val timeout = ConnectionTestService({ delay(1000); provider }, timeoutMillis = 10).test("preset")
        assertEquals("timeout", timeout.errorCode)
        val entered = CompletableDeferred<Unit>()
        val tester = ConnectionTestService({ entered.complete(Unit); awaitCancellation() })
        val job = async { tester.test("preset") }
        entered.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
}
