package com.simpletavern.core.memory

import com.simpletavern.core.llm.ProviderConfig
import com.simpletavern.core.model.*
import kotlin.math.max

/** A conservative Unicode-aware estimate, not a model tokenizer or a billing measurement. */
object ContextTokens {
    const val STRATEGY = "unicode_conservative_v1"

    fun text(value: String?): Int {
        if (value.isNullOrEmpty()) return 0
        var cost = 0L
        var ascii = 0
        var index = 0
        while (index < value.length) {
            val point = value.codePointAt(index)
            index += Character.charCount(point)
            if (point < 128) ascii++ else {
                cost += (ascii.toLong() + 3) / 4
                ascii = 0
                cost += if (point <= 0xffff) 2 else 4
            }
        }
        return (cost + (ascii.toLong() + 3) / 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun message(value: ContextMessage): Int {
        var cost = 4L + text(value.content) + text(value.toolCallsJson) + text(value.providerTurn?.contentJson)
        val mediaById = value.media.mapNotNull { item -> item.attachmentId?.let { it to item } }.toMap()
        for (ref in value.attachments.distinctBy { it.id }) {
            val media = mediaById[ref.id]
            cost += when {
                media?.text != null -> text(media.text).toLong()
                ref.mimeType.startsWith("image/") -> {
                    val tiles = ((ref.width?.toLong() ?: 0) + 511) / 512 * (((ref.height?.toLong() ?: 0) + 511) / 512)
                    max(4096L, (85 + 170 * tiles).coerceAtMost(Int.MAX_VALUE.toLong()))
                }
                else -> max(4096L, ref.size?.coerceAtLeast(0) ?: 0)
            }
        }
        for (media in value.media.filter { item -> value.attachments.none { it.id == item.attachmentId } })
            cost += media.text?.let { text(it).toLong() } ?: max(4096L, media.byteSize.toLong())
        return cost.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun output(provider: ProviderConfig): Int = listOf("max_tokens", "max_output_tokens", "max_completion_tokens", "maxOutputTokens")
        .mapNotNull { provider.extra[it]?.toIntOrNull() }.maxOrNull()?.coerceAtLeast(0)
        ?: if (provider.protocol == LlmProtocol.ANTHROPIC_MESSAGES) 4096 else 0

    fun tools(json: String?): Int = if (json.isNullOrBlank() || json == "[]") 0 else text(json) + 8

    fun requireFits(limit: Int, messages: List<ContextMessage>, outputTokens: Int, toolsJson: String?) {
        if (limit > 0 && messages.sumOf { message(it).toLong() } + outputTokens.coerceAtLeast(0) + tools(toolsJson) > limit)
            throw StError.Validation("request exceeds context_size after reserving output, tools and media; reduce context or increase its limit")
    }
}

/** N excludes the anchor: retain N messages before it. Missing/0 retain none. */
internal fun anchorKeepBefore(count: Int?): Int = (count ?: 0).coerceAtLeast(0)
