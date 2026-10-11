package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
data class AwsBedrockCredentials(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String? = null,
) {
    val credentialMaterials: List<String>
        get() = listOfNotNull(accessKeyId, secretAccessKey, sessionToken).distinct()

    fun validate() {
        if (!accessKeyId.matches(Regex("[A-Za-z0-9]{1,128}")) ||
            !secretAccessKey.isVisibleAsciiCredential() ||
            sessionToken?.isVisibleAsciiCredential() == false
        ) {
            throw StError.Validation("invalid AWS Bedrock credentials")
        }
    }

    override fun toString(): String = "AwsBedrockCredentials(<redacted>)"

    private fun String.isVisibleAsciiCredential(): Boolean =
        isNotEmpty() && length <= 8192 && all { it.code in 33..126 }
}
