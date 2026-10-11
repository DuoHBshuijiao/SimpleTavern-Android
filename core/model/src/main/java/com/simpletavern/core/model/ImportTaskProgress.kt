package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ImportTaskProgress(val taskId: String, val phase: String, val updatedAt: String,
    val fraction: Float = 0f, val files: Int = 0, val bytes: Long = 0,
    val committed: Boolean = false, val batchId: String = taskId, val error: String? = null,
    val inputBytes: Long = 0)
