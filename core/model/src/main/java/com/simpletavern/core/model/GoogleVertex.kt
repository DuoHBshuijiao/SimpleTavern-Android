package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
data class GoogleVertexOptions(
    val projectId: String,
    val location: String = "global",
    val apiVersion: String = "v1",
)
