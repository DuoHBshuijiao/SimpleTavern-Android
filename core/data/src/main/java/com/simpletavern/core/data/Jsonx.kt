package com.simpletavern.core.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object Jsonx {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    inline fun <reified T> encode(value: T): String = json.encodeToString(value)
    inline fun <reified T> decode(text: String): T = json.decodeFromString(text)
    inline fun <reified T> decodeOrNull(text: String?): T? =
        text?.let { runCatching { decode<T>(it) }.getOrNull() }
}
