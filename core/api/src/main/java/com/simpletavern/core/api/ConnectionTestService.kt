package com.simpletavern.core.api

import com.simpletavern.core.data.TokenRequestAccounting
import com.simpletavern.core.data.UsageStore
import com.simpletavern.core.llm.*
import com.simpletavern.core.model.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** One explicit small generation. No chat, cache creation, tool execution or automatic retry. */
internal class ConnectionTestService(
    private val resolve: suspend (String) -> ProviderConfig,
    private val client: LlmClient = LlmClient(),
    private val ledger: UsageStore? = null,
    private val timeoutMillis: Long = 30_000,
) {
    suspend fun test(presetId: String): ConnectionTestResult {
        val start = System.nanoTime()
        fun elapsed() = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(0)
        var provider: ProviderConfig? = null
        var tokens = UsageTokens()
        var completed = false
        try {
            val finished = withTimeoutOrNull(timeoutMillis) {
                val resolved = resolve(presetId)
                val active = resolved.copy(toolsJson = null, cachedContext = null, cachePlan = null,
                    temperature = null, topP = null,
                    extra = resolved.extra.filterKeys { it == "max_token_field" } + ("max_tokens" to "32"))
                provider = active
                val request = LlmRequest(UUID.randomUUID().toString(), active.protocol, active.model,
                    listOf(ContextMessage(ChatRole.user, "Reply with OK only.")), extra = active.extra)
                suspend fun collect(beforeExecute: suspend () -> Unit) {
                    try {
                        client.stream(active, request, beforeExecute).collect { event -> when (event) {
                            is StreamEvent.Usage -> tokens = event.usage
                            is StreamEvent.Done -> { tokens = event.usage ?: tokens; completed = event.ok }
                            is StreamEvent.Error -> throw StError.Provider("connection test response failed")
                            else -> Unit
                        } }
                    } catch (error: StError.Provider) {
                        // A recognized output-limit terminal frame still proves this model accepted the request.
                        if (error.details["finishReason"] in setOf("length", "max_tokens", "MAX_TOKENS", "max_output_tokens")) completed = true
                        else throw error
                    }
                    if (!completed) throw StError.Provider("connection test response was incomplete")
                }
                val store = ledger
                if (store == null) collect({}) else store.executeTokenRequest(request.requestId, null, active.providerId, active.model,
                    UsageRecordContext(active.protocol.name, startedAt = Instant.now().toString(), requestedModel = active.model,
                        purpose = "connection_test", scope = "preset:$presetId"), active.apiKey,
                    outcome = { TokenRequestOutcome(tokens, completed, totalDurationMs = elapsed()) },
                    credentialMaterials = active.credentialMaterials, run = ::collect)
                true
            }
            val active = provider
            return if (finished == true) ConnectionTestResult(true, elapsed(), active?.protocol, active?.model,
                active?.let { TokenRequestAccounting.safeTokens(tokens, it.credentialMaterials) })
            else ConnectionTestResult(false, elapsed(), active?.protocol, active?.model, errorCode = "timeout", message = "连接测试超时")
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val active = provider
            val code = (error as? StError)?.code ?: if (error is java.io.IOException) "network" else "connection_test_failed"
            val status = (error as? StError)?.details?.get("httpStatus")?.toIntOrNull()?.takeIf { it in 100..599 }
            return ConnectionTestResult(false, elapsed(), active?.protocol, active?.model, errorCode = code,
                message = status?.let { "提供商返回 HTTP $it" } ?: when (code) {
                    "validation" -> "请检查预设的模型、端点和凭据配置"
                    "not_found" -> "找不到指定预设"
                    "unsupported" -> "当前连接方式不支持此测试"
                    "accounting" -> "测试用量记录未完成，请勿自动重试"
                    else -> "连接测试失败，请检查网络和预设配置"
                })
        }
    }
}
