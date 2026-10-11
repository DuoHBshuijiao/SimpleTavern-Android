package com.simpletavern.core.model

import kotlinx.serialization.Serializable

/** Source path/URI is deliberately absent; the destination belongs to the selected sandbox. */
@Serializable
data class FileCopyProgress(val taskId: String, val sandboxId: String, val requestedTarget: String,
    val updatedAt: String, val phase: String = "queued", val inputBytes: Long = 0,
    val targetPath: String? = null, val published: Boolean = false, val error: String? = null,
    val publicationReconciled: Boolean = false, val exactTarget: Boolean = false)
