package com.simpletavern.core.model

sealed class StError(
    open val code: String,
    override val message: String,
    open val details: Map<String, String> = emptyMap(),
) : Exception(message) {
    data class NotFound(override val message: String, override val details: Map<String, String> = emptyMap()) :
        StError("not_found", message, details)

    data class Conflict(override val message: String, override val details: Map<String, String> = emptyMap()) :
        StError("conflict", message, details)

    data class Validation(override val message: String, override val details: Map<String, String> = emptyMap()) :
        StError("validation", message, details)

    data class Unsupported(override val message: String, override val details: Map<String, String> = emptyMap()) :
        StError("unsupported", message, details)

    data class Cancelled(override val message: String = "cancelled") :
        StError("cancelled", message)

    data class Provider(override val message: String, override val details: Map<String, String> = emptyMap()) :
        StError("provider", message, details)

    data class Sandbox(override val message: String, override val details: Map<String, String> = emptyMap()) :
        StError("sandbox", message, details)

    data class ApprovalRequired(val approvalId: String, override val message: String = "approval_required") :
        StError("approval_required", message, mapOf("approvalId" to approvalId))

    data class Interrupted(override val message: String = "interrupted") :
        StError("interrupted", message)
}
