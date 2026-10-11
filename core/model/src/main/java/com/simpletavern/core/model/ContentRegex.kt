package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ContentRegexRule(
    val id: String,
    val name: String? = null,
    val enabled: Boolean = true,
    val order: Int = 0,
    val pattern: String = "",
    val action: String = "remove",
    val replacement: String? = null,
    val matchMode: String = "global",
    val scanDepthOverride: Int? = null,
    val extractSource: String = "whole_match",
    val extractGroupIndex: Int? = null,
    val engine: String = "re2",
    val roles: Set<ChatRole> = setOf(ChatRole.user, ChatRole.assistant),
    val visualOnly: Boolean = false,
    val applyToReasoning: Boolean = false,
    val scanAllHistory: Boolean = false,
    val skipOpeningMessage: Boolean = true,
)

@Serializable
data class RegexExtraction(
    val ruleId: String, val ruleName: String, val action: String, val value: String,
    val matchedText: String, val start: Int, val end: Int,
)

@Serializable
data class RegexRuleError(val ruleId: String, val code: String, val message: String)

@Serializable
data class ContentRegexOutput(
    val persistedText: String, val displayText: String,
    val extractedItems: List<RegexExtraction> = emptyList(),
    val errors: List<RegexRuleError> = emptyList(),
    val sourceProcessed: Boolean = false,
    val messageRole: ChatRole? = null,
)

@Serializable
data class ContentRegexRecord(
    val messageId: String, val chatId: String, val nodeId: String, val orderIndex: Long,
    val revision: Long, val sourceText: String, val rulesJson: String,
    val output: ContentRegexOutput, val queueStatus: String,
    val consumedByTaskId: String? = null, val updatedAt: String,
)

@Serializable
data class MessageContentProjection(
    val messageId: String,
    val content: String,
    val displayContent: String,
    val reasoningContent: String? = null,
    val displayReasoningContent: String? = null,
    val errors: List<RegexRuleError> = emptyList(),
)
