package com.simpletavern.core.api

import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.data.NativeDatabaseSnapshot
import com.simpletavern.core.data.NativeJsonInput
import com.simpletavern.core.data.db.AppDatabase
import com.simpletavern.core.model.*
import java.io.File
import java.nio.file.Paths
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Structural preflight only. Restore must still validate all rows/references and conflicts before commit. */
class NativeBackupPreflightService(private val db: AppDatabase) {
    suspend fun inspect(archive: File): NativeBackupPreflight = withContext(Dispatchers.IO) {
        ZipFile(archive).use { zip ->
            val entries = linkedMapOf<String, java.util.zip.ZipEntry>()
            var totalBytes = 0L
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                currentCoroutineContext().ensureActive()
                val entry = enumeration.nextElement()
                validateArchivePath(entry.name.removeSuffix("/"))
                if (entries.size >= 200_030 || entries.put(entry.name, entry) != null) throw StError.Validation("too many or duplicate native archive entries")
                if (entry.size < 0 || entry.size > NativeBackupService.MAX_BYTES - totalBytes) throw StError.Validation("native archive sizes exceed budget")
                totalBytes += entry.size
            }
            val metadata = entries["BACKUP_META.json"] ?: throw StError.Unsupported("archive has no native backup manifest")
            if (metadata.isDirectory || metadata.size > 16_000_000) throw StError.Validation("native manifest exceeds budget")
            val text = zip.getInputStream(metadata).use { NativeJsonInput.read(it, 16_000_000) }
            val raw = NativeJsonInput.parse(text) as? JsonObject ?: throw StError.Validation("native manifest is not an object")
            if (raw["format"]?.jsonPrimitive?.contentOrNull != "simpletavern-native")
                throw StError.Unsupported("legacy partial backup format has no native schema/restore contract")
            // Keep streaming boolean decoding for legacy case-insensitive values such as "TRUE".
            val manifest = Jsonx.decode<NativeBackupManifest>(raw.toString())
            if (raw["version"]?.jsonPrimitive?.intOrNull != 1 || raw["schemaVersion"]?.jsonPrimitive?.intOrNull != 2 ||
                manifest.version != 1 || manifest.schemaVersion != 2) throw StError.Unsupported("unsupported or missing native backup/schema version")
            if (manifest.options.includeRebuildableRuntimes) throw StError.Unsupported("portable bundled runtimes are not implemented")
            val expected = NativeDatabaseSnapshot.selectedTables(manifest.options)
            if (manifest.tables.map { it.name }.toSet() != expected.toSet() || manifest.tables.size != expected.size ||
                manifest.omittedTables.toSet() != (NativeDatabaseSnapshot.ALL_TABLES - expected.toSet()).toSet() ||
                manifest.omittedTables.distinct().size != manifest.omittedTables.size)
                throw StError.Validation("native table selection does not match declared options")
            val allowed = mutableSetOf("BACKUP_META.json")
            var rows = 0L
            for (table in manifest.tables) {
                currentCoroutineContext().ensureActive()
                if (table.rows < 0 || table.rows > 2_000_000 - rows || table.archivePath != "database/${table.name}.jsonl")
                    throw StError.Validation("invalid native table descriptor")
                rows += table.rows
                val schema = NativeDatabaseSnapshot(db).schemaColumns(table.name)
                if (schema != table.columns) throw StError.Unsupported("native columns do not match schema v2")
                if (entries[table.archivePath]?.isDirectory != false) throw StError.Validation("native table file missing")
                allowed += table.archivePath
            }
            if (manifest.files.size > 200_000 || manifest.files.map { it.path }.distinct().size != manifest.files.size)
                throw StError.Validation("invalid native file manifest cardinality")
            val fileTypes = manifest.files.associate { it.path to it.kind }
            for (file in manifest.files) {
                validateArchivePath(file.path)
                val attachments = file.path == "attachments" || file.path.startsWith("attachments/")
                val sandbox = file.path == "sandboxes" || file.path.startsWith("sandboxes/")
                if ((!attachments && !sandbox) || attachments && !manifest.options.includeAttachments || sandbox && !manifest.options.includeSandboxUserFiles)
                    throw StError.Validation("native file is outside selected managed roots")
                if (sandbox && file.path != "sandboxes" && !file.path.substringAfter('/').substringBefore('/').matches(Regex("[A-Za-z0-9_-]{1,128}")))
                    throw StError.Validation("invalid native sandbox identity")
                if ((file.path == "attachments" || file.path == "sandboxes") && file.kind != "directory")
                    throw StError.Validation("managed backup root is not a directory")
                validateFileMode(file)
                var parent = file.path.substringBeforeLast('/', "")
                while (parent.isNotEmpty()) {
                    if (fileTypes[parent] != "directory") throw StError.Validation("native file parent is missing, linked or not a directory")
                    parent = parent.substringBeforeLast('/', "")
                }
                when (file.kind) {
                    "file" -> {
                        val entry = entries[file.path] ?: throw StError.Validation("native user file missing")
                        if (entry.isDirectory || file.size < 0 || entry.size != file.size || file.linkTarget != null)
                            throw StError.Validation("native user file descriptor differs from archive")
                        allowed += file.path
                    }
                    "directory" -> {
                        if (file.size != 0L || file.linkTarget != null || entries["${file.path}/"]?.isDirectory != true || entries["${file.path}/"]?.size != 0L)
                            throw StError.Validation("native directory entry missing")
                        allowed += "${file.path}/"
                    }
                    "symlink" -> {
                        if (file.size != 0L) throw StError.Validation("native symbolic link has a data size")
                        if (file.linkTarget == null) throw StError.Validation("native link target missing")
                        if (entries.containsKey(file.path) || entries.containsKey("${file.path}/")) throw StError.Validation("native symbolic link must not have a data entry")
                    }
                    else -> throw StError.Unsupported("unsupported native file kind")
                }
            }
            if (manifest.credentialsIncluded != manifest.options.includeCredentials) throw StError.Validation("native credential policy differs from options")
            if (manifest.credentialsIncluded) {
                if (manifest.credentialArchivePath != "credentials/encrypted.json" || entries[manifest.credentialArchivePath]?.isDirectory != false ||
                    entries[manifest.credentialArchivePath]!!.size > 3_000_000) throw StError.Validation("native encrypted credential entry missing or oversized")
                allowed += "credentials/encrypted.json"
            } else if (manifest.credentialArchivePath != null) throw StError.Validation("excluded credentials have an archive path")
            if (entries.keys != allowed) throw StError.Validation("native archive includes missing or undeclared entries")
            try { java.time.Instant.parse(manifest.createdAt) }
            catch (_: Exception) { throw StError.Validation("invalid native snapshot time") }
            // validateArchive performs link target validation and combined topology checks.
            val linkNotes = NativeBackupLinkResolver.validateArchive(manifest.files)
            NativeBackupPreflight(manifest, totalBytes, manifest.notes + linkNotes +
                "Structural preflight only: row content/references/conflicts, password decryption and restore transaction are not validated here.")
        }
    }
    companion object {
        fun validateFileMode(file: NativeBackupFile) {
            file.mode?.let { mode ->
                val type = mode and 61440
                val expectedType = when (file.kind) { "file" -> 32768; "directory" -> 16384; "symlink" -> 40960; else -> -1 }
                if (mode < 0 || mode and 3072 != 0 || mode and 512 != 0 && file.kind != "directory" ||
                    type != 0 && type != expectedType || mode and 65535 != mode)
                    throw StError.Unsupported("native v1 does not support privileged or mismatched file modes")
            }
        }
        fun validateArchivePath(path: String) {
            if (path.isBlank() || path.startsWith('/') || path.contains('\\') || path.contains('\u0000') ||
                path.split('/').any { it.isEmpty() || it == "." || it == ".." }) throw StError.Validation("invalid native archive path")
        }
        fun validateLinkTarget(path: String, target: String) {
            val parts = path.split('/')
            if (parts.size < 3 || parts[0] != "sandboxes" || target.isEmpty() || target.contains('\\') ||
                target.contains('\u0000') || Paths.get(target).isAbsolute) throw StError.Validation("native link is not portable")
            val sandboxRoot = Paths.get("sandboxes", parts[1])
            if (!Paths.get(path).parent.resolve(target).normalize().startsWith(sandboxRoot)) throw StError.Validation("native link escapes its sandbox")
        }
    }
}
