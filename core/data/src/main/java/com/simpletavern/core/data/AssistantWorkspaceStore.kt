package com.simpletavern.core.data

import androidx.room.withTransaction
import com.simpletavern.core.data.db.AppDatabase
import com.simpletavern.core.data.db.SourceBlobEntity
import com.simpletavern.core.model.*
import java.time.Instant

/** Persistent text workspace. Logical files live in Room and are independent from executable sandbox paths. */
class AssistantWorkspaceStore(private val db: AppDatabase) {
    suspend fun get(assistantId: String, path: String): AssistantWorkspaceFile? =
        db.imports().getBlob(identity(assistantId, path))?.let { Jsonx.decode(it.contentJson) }

    suspend fun list(assistantId: String, afterPath: String = "", limit: Int = 100): WorkspaceFilePage {
        if (limit !in 1..200) throw StError.Validation("workspace page limit must be 1..200")
        val rows = db.imports().workspaceFiles(namespace(assistantId), afterPath, limit + 1)
        val files = rows.take(limit)
        return WorkspaceFilePage(files, files.lastOrNull()?.path, rows.size > limit)
    }

    suspend fun write(assistantId: String, path: String, text: String, expectedContent: String?): AssistantWorkspaceFile = db.withTransaction {
        validateContent(text)
        val current = get(assistantId, path)
        if (current?.content != expectedContent) throw StError.Conflict("workspace file changed; read it again before writing")
        val changed = (current ?: AssistantWorkspaceFile(assistantId, normalize(path), text, ""))
            .copy(content = text, updatedAt = Instant.now().toString())
        saveImported(changed)
        changed
    }

    suspend fun delete(assistantId: String, path: String, expectedContent: String) = db.withTransaction {
        val current = get(assistantId, path) ?: throw StError.NotFound("workspace file")
        if (current.content != expectedContent) throw StError.Conflict("workspace file changed; read it again before deleting")
        db.imports().deleteBlob(identity(assistantId, path))
    }

    /** ImportMerge supplies the merged record, including its local edits and source provenance. */
    suspend fun saveImported(file: AssistantWorkspaceFile) {
        validateContent(file.content)
        val normalized = file.copy(path = normalize(file.path))
        db.imports().upsertBlob(SourceBlobEntity(identity(file.assistantId, file.path), namespace(file.assistantId),
            normalized.path, Jsonx.encode(normalized), normalized.updatedAt))
    }

    suspend fun getAsset(assistantId: String, path: String): AssistantWorkspaceAsset? =
        db.imports().getBlob(assetIdentity(assistantId, path))?.let { Jsonx.decode(it.contentJson) }

    suspend fun listAssets(assistantId: String, afterPath: String = "", limit: Int = 100): WorkspaceFilePage {
        if (limit !in 1..200) throw StError.Validation("workspace page limit must be 1..200")
        val rows = db.imports().workspaceFiles(assetNamespace(assistantId), afterPath, limit + 1)
        val files = rows.take(limit)
        return WorkspaceFilePage(files, files.lastOrNull()?.path, rows.size > limit)
    }

    suspend fun saveImportedAsset(asset: AssistantWorkspaceAsset) = db.imports().upsertBlob(SourceBlobEntity(
        assetIdentity(asset.assistantId, asset.path), assetNamespace(asset.assistantId), normalize(asset.path), Jsonx.encode(asset), asset.updatedAt))

    suspend fun deleteAsset(assistantId: String, path: String) = db.imports().deleteBlob(assetIdentity(assistantId, path))

    companion object {
        const val MAX_TEXT_BYTES = 262_144
        fun namespace(assistantId: String) = "assistant-workspace:${assistantId.length}:$assistantId"
        fun identity(assistantId: String, path: String) = "${namespace(assistantId)}:${normalize(path)}"
        fun assetNamespace(assistantId: String) = "assistant-workspace-assets:${assistantId.length}:$assistantId"
        fun assetIdentity(assistantId: String, path: String) = "${assetNamespace(assistantId)}:${normalize(path)}"
        fun normalize(path: String): String {
            val clean = path.replace('\\', '/')
            if (clean.isBlank() || clean.length > 512 || clean.startsWith('/') || clean.any { it.isISOControl() || it == ':' } ||
                clean.split('/').any { it.isEmpty() || it == "." || it == ".." }) throw StError.Validation("workspace path must be relative without traversal")
            return clean
        }
        fun validateContent(text: String) {
            if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) throw StError.Validation("workspace text file exceeds 256 KiB")
        }
    }
}
