package com.simpletavern.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserQuestion(
    val id: String,
    val question: String,
    val options: List<String> = emptyList(),
    @SerialName("selection_type") val selectionType: String = "text",
)

@Serializable
data class UserQuestionAnswer(
    val selectedOptions: List<String> = emptyList(),
    val text: String? = null,
)

@Serializable
data class UserQuestionRequest(
    val request: ApprovalRequest,
    val questions: List<UserQuestion>,
    val answers: Map<String, UserQuestionAnswer>? = null,
)
