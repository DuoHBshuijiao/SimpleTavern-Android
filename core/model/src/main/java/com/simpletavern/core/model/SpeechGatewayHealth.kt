package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SpeechGatewayHealthRequest(val presetId: String? = null, val chatId: String? = null,
    val assistantId: String? = null, val includeVoiceCloneGateway: Boolean = true)

/** Reachability of one configured gateway, not proof that its model can synthesize. */
@Serializable
data class SpeechGatewayHealthCheck(val role: String, val state: String, val httpStatus: Int? = null,
    val errorCode: String? = null)

@Serializable
data class SpeechGatewayHealth(val selection: SpeechDirectorySelection, val checks: List<SpeechGatewayHealthCheck>,
    val checkedAt: String, val reachable: Boolean, val voiceCloneGatewayConfigured: Boolean = false,
    val warnings: List<String> = listOf("gateway_health_not_synthesis_availability_proof"), val version: Int = 1) {
    override fun toString() = "SpeechGatewayHealth(provider=${selection.provider}, checks=${checks.size}, reachable=$reachable)"
}
