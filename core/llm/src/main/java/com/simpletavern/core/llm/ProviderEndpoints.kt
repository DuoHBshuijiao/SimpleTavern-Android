package com.simpletavern.core.llm

import com.simpletavern.core.model.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The configured root is also used for file operations; never route a proxy upload to the vendor. */
internal object ProviderEndpoints {
    fun operation(p: ProviderConfig, defaultVersion: String, vararg parts: String): HttpUrl {
        val base = p.baseUrl.trimEnd('/').toHttpUrlOrNull() ?: throw StError.Validation("invalid provider URL")
        if (base.username.isNotEmpty() || base.password.isNotEmpty() || base.query != null || base.fragment != null)
            throw StError.Validation("provider URL cannot contain credentials, query, or fragment")
        val path = base.encodedPath.trimEnd('/')
        val generationOperation = (p.protocol == LlmProtocol.OPENAI_COMPATIBLE && parts.contentEquals(arrayOf("chat", "completions"))) ||
            (p.protocol == LlmProtocol.OPENAI_RESPONSES && parts.contentEquals(arrayOf("responses")))
        val generationPath = p.generationPath
        if (generationOperation && generationPath != null) {
            if (generationPath.length > 4096 || generationPath.any(Char::isISOControl) ||
                generationPath.contains('\\') || generationPath.contains('?') || generationPath.contains('#') ||
                generationPath.isNotEmpty() && !generationPath.startsWith('/'))
                throw StError.Validation("invalid generation path")
            val customPath = path + generationPath
            return base.newBuilder().encodedPath(customPath.ifEmpty { "/" }).build()
        }
        if (path.endsWith("/" + parts.joinToString("/"))) return base
        val suffix = listOf("/chat/completions", "/responses", "/messages").firstOrNull { path.endsWith(it) }
        val geminiOperation = if (p.protocol == LlmProtocol.GEMINI_GENERATE_CONTENT)
            Regex("/models/[^/]+:(?:streamGenerateContent|generateContent)$").find(path)?.value else null
        val root = if (geminiOperation != null) path.removeSuffix(geminiOperation) else if (suffix == null) path else path.removeSuffix(suffix)
        return base.newBuilder().apply {
            encodedPath(if (root.isEmpty()) "/" else root)
            if (root.isEmpty() && !p.unversionedRoot) addPathSegment(defaultVersion)
            parts.forEach { addPathSegment(it) }
        }.build()
    }

    fun geminiUpload(p: ProviderConfig): HttpUrl {
        val files = operation(p, "v1beta", "files")
        val root = files.encodedPath.removeSuffix("/files")
        val version = root.substringAfterLast('/')
        if (version !in setOf("v1beta", "v1alpha", "v1"))
            throw StError.Unsupported("Gemini Files upload requires a versioned API root")
        return files.newBuilder().encodedPath(root.substringBeforeLast('/') + "/upload/$version/files").build()
    }
}
