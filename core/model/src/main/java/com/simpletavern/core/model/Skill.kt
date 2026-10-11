package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SkillDefinition(
    val id: String,
    val scope: String,
    val directory: String,
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val updatedAt: String,
    val sourceNamespace: String? = null,
    val builtin: Boolean = false,
)

@Serializable
data class SkillFile(
    val skillId: String,
    val path: String,
    val content: String? = null,
    val attachment: AttachmentRef? = null,
    val updatedAt: String,
    val sourceNamespace: String? = null,
)

@Serializable
data class AssistantSkillInfo(val skill: SkillDefinition, val enabled: Boolean)

@Serializable
data class SkillInstallOptions(val skillPath: String? = null, val directory: String? = null)

@Serializable
data class GitHubSkillSource(val owner: String, val repository: String, val ref: String? = null)

@Serializable
data class SkillInstallResult(val skill: SkillDefinition, val installedFiles: Int, val directoryRenamed: Boolean)

@Serializable
data class SkillSandboxCopiedFile(val path: String, val progress: FileCopyProgress)

/** Partial copies remain in the selected sandbox; their original task IDs stay observable. */
@Serializable
data class SkillSandboxCopyResult(val skillId: String, val sandboxId: String, val targetDirectory: String,
    val files: List<SkillSandboxCopiedFile>, val completed: Boolean, val failedPath: String? = null, val error: String? = null)

/** Package file classification shared by full-source import and explicit skill installation. */
object SkillFileTypes {
    private val binary = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "pdf", "zip", "gz", "tar", "7z", "mp3", "mp4", "wav", "ogg", "otf", "ttf", "woff", "woff2", "xls", "xlsx", "doc", "docx", "ppt", "pptx", "db", "sqlite", "sqlite3", "wasm", "bin", "exe")
    fun isBinary(path: String) = path.substringAfterLast('.', "").lowercase() in binary
    fun text(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString().takeIf { '\u0000' !in it }
    } catch (_: java.nio.charset.CharacterCodingException) { null }
}
