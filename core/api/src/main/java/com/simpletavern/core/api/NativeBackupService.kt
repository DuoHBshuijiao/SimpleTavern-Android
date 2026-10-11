package com.simpletavern.core.api

import android.os.Build
import android.system.Os
import android.system.OsConstants
import com.simpletavern.core.data.*
import com.simpletavern.core.data.db.AppDatabase
import com.simpletavern.core.model.*
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** An exclusive facade snapshot plus one Room read transaction. Never claims host shell isolation. */
class NativeBackupService(
    private val db: AppDatabase, private val paths: StoragePaths, private val tasks: TaskStore,
    private val gate: DataOperationGate, private val credentials: CredentialStore,
) {
    private val snapshots = NativeDatabaseSnapshot(db)
    suspend fun export(taskId: String, destination: File, options: BackupOptions, password: CharArray?, overwrite: Boolean,
                       progress: suspend (BackupTaskProgress, Boolean) -> Unit): File = withContext(Dispatchers.IO) {
        if (options.includeCredentials) CredentialArchiveCipher.validatePassword(password)
        if (!taskId.matches(Regex("[a-f0-9]{32}"))) throw StError.Validation("invalid backup task identity")
        if (options.includeRebuildableRuntimes) throw StError.Unsupported("runtime executables/base are supplied by the APK, not exported as a portable runtime; includeSandboxUserFiles includes initialized guest files")
        val dest = destination.canonicalFile
        validateDestination(dest)
        val lease = gate.exclusiveLease(taskId)
        val stage = paths.backups.resolve("operations/$taskId")
        var stageOwned = false
        var temporary: File? = null
        try {
            if (tasks.nonTerminal().any { it.id != taskId }) throw StError.Conflict("active tasks prevent a consistent native backup")
            if (!stage.mkdirs()) throw StError.Conflict("backup staging already exists or is unavailable")
            stageOwned = true
            if (dest.exists() && (!overwrite || !dest.isFile)) throw StError.Conflict("backup destination exists or is not a regular file")
            if (!dest.parentFile!!.isDirectory && !dest.parentFile!!.mkdirs()) throw StError.Validation("backup destination directory unavailable")
            var state = BackupTaskProgress(taskId, "database", now(), outputPath = dest.path)
            progress(state, true)
            val tables = snapshots.export(stage, options, taskId) { rows ->
                state = state.copy(rows = rows, updatedAt = now())
                progress(state, false) // Only in-memory events while holding the Room snapshot transaction.
            }
            state = state.copy(phase = "files", updatedAt = now())
            progress(state, true)
            val files = mutableListOf<NativeBackupFile>()
            var fileManifestBytes = 0L
            fun addMetadata(entry: NativeBackupFile) {
                NativeBackupPreflightService.validateFileMode(entry)
                fileManifestBytes += Jsonx.encode(entry).toByteArray(Charsets.UTF_8).size + 1L
                if (fileManifestBytes > 16_000_000) throw StError.Validation("native file metadata exceeds manifest budget")
                files += entry
            }
            var totalBytes = 0L
            var lastCheckpoint = 0L
            val temp = File(dest.parentFile, ".st-backup-$taskId-${UUID.randomUUID()}.tmp")
            withContext(NonCancellable) {
                writePublicationJournal(stage, temp, dest, created = false)
                if (!temp.createNewFile()) throw StError.Conflict("backup temporary target already exists")
                temporary = temp
                writePublicationJournal(stage, temp, dest, created = true)
            }
            temp.outputStream().use { fileOutput ->
                ZipOutputStream(fileOutput).use { zip ->
                    val buffer = ByteArray(65_536)
                    suspend fun addFile(file: File, archivePath: String, mode: Int? = null) {
                        currentCoroutineContext().ensureActive()
                        val before = Os.lstat(file.path)
                        NativeBackupPreflightService.validateArchivePath(archivePath)
                        if (!OsConstants.S_ISREG(before.st_mode)) throw StError.Validation("backup file became linked or special")
                        zip.putNextEntry(ZipEntry(archivePath))
                        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
                        var count = 0L
                        FileInputStream(fd).use { input ->
                            val opened = Os.fstat(fd)
                            if (opened.st_dev != before.st_dev || opened.st_ino != before.st_ino) throw StError.Conflict("backup file changed before reading")
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                count += n; totalBytes += n
                                if (totalBytes > MAX_BYTES) throw StError.Validation("native archive exceeds 8 GiB uncompressed data")
                                zip.write(buffer, 0, n)
                                val time = System.nanoTime() / 1_000_000
                                if (time - lastCheckpoint >= 500) {
                                    state = state.copy(bytes = totalBytes, files = files.size, updatedAt = now())
                                    progress(state, true); lastCheckpoint = time
                                }
                            }
                        }
                        val after = Os.lstat(file.path)
                        if (count != before.st_size || after.st_size != before.st_size || after.st_ino != before.st_ino ||
                            after.st_dev != before.st_dev || after.st_mtime != before.st_mtime)
                            throw StError.Conflict("backup file changed while reading")
                        zip.closeEntry()
                        if (mode != null) addMetadata(NativeBackupFile(archivePath, "file", count, mode))
                    }
                    for (table in tables) addFile(stage.resolve(table.archivePath), table.archivePath)
                    suspend fun addTree(root: File, prefix: String, sandbox: Boolean) {
                        if (root.canonicalFile != root.absoluteFile) throw StError.Validation("linked backup root rejected")
                        val queue = ArrayDeque<File>().apply { add(root) }
                        while (queue.isNotEmpty()) {
                            currentCoroutineContext().ensureActive()
                            val file = queue.removeFirst()
                            val relative = file.relativeTo(root).invariantSeparatorsPath
                            val name = if (relative.isEmpty()) prefix else "$prefix/$relative"
                            NativeBackupPreflightService.validateArchivePath(name)
                            val info = Os.lstat(file.path)
                            if (files.size >= 200_000) throw StError.Validation("native file manifest exceeds 200000 entries")
                            when {
                                OsConstants.S_ISDIR(info.st_mode) -> {
                                    if (sandbox && file.parentFile == root && !file.name.matches(SANDBOX_DIRECTORY_NAME))
                                        throw StError.Validation("invalid sandbox directory in native snapshot")
                                    zip.putNextEntry(ZipEntry("$name/")); zip.closeEntry()
                                    addMetadata(NativeBackupFile(name, "directory", mode = info.st_mode))
                                    val children = file.listFiles() ?: throw StError.Validation("cannot enumerate backup directory")
                                    if (children.size + queue.size + files.size > 200_000) throw StError.Validation("native file manifest exceeds 200000 entries")
                                    children.sortedBy { it.name }.forEach(queue::add)
                                }
                                OsConstants.S_ISREG(info.st_mode) -> addFile(file, name, info.st_mode)
                                OsConstants.S_ISLNK(info.st_mode) -> {
                                    if (!sandbox || file.parentFile == root) throw StError.Validation("linked attachment or sandbox root is not portable")
                                    val target = NativeBackupLinkResolver.portableTarget(name, Os.readlink(file.path))
                                    addMetadata(NativeBackupFile(name, "symlink", mode = info.st_mode, linkTarget = target))
                                }
                                else -> throw StError.Unsupported("special files cannot be included in a native backup")
                            }
                        }
                    }
                    if (options.includeAttachments) addTree(paths.attachments, "attachments", false)
                    if (options.includeSandboxUserFiles) addTree(paths.sandboxes, "sandboxes", true)
                    validateCapturedFiles(stage, tables, files, options)
                    val linkNotes = NativeBackupLinkResolver.validateArchive(files)
                    val secretPath = if (options.includeCredentials) "credentials/encrypted.json" else null
                    if (secretPath != null) {
                        currentCoroutineContext().ensureActive()
                        val encrypted = CredentialArchiveCipher.encrypt(credentials.snapshotSecrets(), password!!)
                        totalBytes += encrypted.toByteArray(Charsets.UTF_8).size
                        if (totalBytes > MAX_BYTES) throw StError.Validation("native archive exceeds 8 GiB")
                        putText(zip, secretPath, encrypted)
                        currentCoroutineContext().ensureActive()
                    }
                    val environment = buildJsonObject {
                        put("androidSdk", Build.VERSION.SDK_INT); put("abis", JsonArray(Build.SUPPORTED_ABIS.map(::JsonPrimitive)))
                        put("shell", "proot-alpine"); put("shellIsolation", false); put("bundledRootfs", true)
                        put("runtime", com.simpletavern.runtime.sandbox.SandboxRuntime.VERSION)
                        put("runtimeRebuild", "compatible arm64 APK supplies PRoot/base; initialized guest files require includeSandboxUserFiles; kernel isolation and device execution remain unverified")
                    }.toString()
                    val manifest = NativeBackupManifest(createdAt = now(), options = options, tables = tables,
                        files = files, omittedTables = NativeDatabaseSnapshot.ALL_TABLES - tables.map { it.name }.toSet(),
                        credentialsIncluded = secretPath != null, credentialArchivePath = secretPath, environmentJson = environment,
                        notes = listOf("Current backup task excluded; never replay tasks or pending approvals on restore.",
                            "Optionally omitted tables/files can leave references outside this archive; this is not a complete snapshot when options are disabled.",
                            "Known credential fields in configuration/source/diagnostic JSON are redacted. Chat text and user files can still contain private data.",
                            "No content hashes. PRoot guest paths are not kernel isolation; process/file boundaries still require runtime/device proof.") + linkNotes)
                    val manifestJson = Jsonx.encode(manifest)
                    val manifestBytes = manifestJson.toByteArray(Charsets.UTF_8).size
                    if (manifestBytes > 16_000_000) throw StError.Validation("native manifest exceeds 16000000 UTF-8 bytes")
                    totalBytes += manifestBytes
                    if (totalBytes > MAX_BYTES) throw StError.Validation("native archive exceeds 8 GiB")
                    putText(zip, "BACKUP_META.json", manifestJson)
                    zip.finish(); zip.flush(); fileOutput.fd.sync()
                }
            }
            currentCoroutineContext().ensureActive()
            state = state.copy(phase = "publishing", files = files.size, bytes = totalBytes, updatedAt = now())
            progress(state, true)
            withContext(NonCancellable) {
                // A complete archive is linked atomically without overwriting, or explicitly renamed when allowed.
                if (overwrite) Os.rename(temp.path, dest.path) else { Os.link(temp.path, dest.path); temp.delete() }
                progress(state.copy(phase = "done", updatedAt = now()), true)
            }
            dest
        } finally {
            withContext(NonCancellable) {
                try { temporary?.delete(); if (stageOwned) deleteStage(stage) } finally { lease.close() }
            }
        }
    }

    fun validateDestination(destination: File) {
        val dest = destination.canonicalFile
        val appRoot = paths.root.parentFile!!.parentFile!!.canonicalFile
        val backups = paths.backups.canonicalFile
        if (backups != paths.backups.absoluteFile) throw StError.Validation("linked backup storage rejected")
        if ((dest == appRoot || dest.path.startsWith(appRoot.path + File.separator)) &&
            !dest.path.startsWith(backups.path + File.separator)) throw StError.Validation("native backup must not overwrite live app data")
        if (dest == backups || dest.path.startsWith(backups.resolve("operations").path + File.separator))
            throw StError.Validation("native backup destination cannot be an operation staging directory")
        if (dest == backups.resolve("operations")) throw StError.Validation("backup destination is reserved for operation staging")
    }

    private suspend fun validateCapturedFiles(stage: File, tables: List<NativeBackupTable>, files: List<NativeBackupFile>, options: BackupOptions) {
        val captured = files.associate { it.path to it.kind }
        fun requireAttachment(path: String?) {
            if (path.isNullOrBlank()) return
            // A historical remote/data URI is metadata, not a managed local file or an external mount.
            if (path.startsWith("https:") || path.startsWith("http:") || path.startsWith("data:")) return
            val archivePath = "attachments/$path"
            NativeBackupPreflightService.validateArchivePath(archivePath)
            if (captured[archivePath] != "file") throw StError.Validation("referenced managed attachment is missing from the native snapshot")
        }
        val checked = setOf("attachments", "characters", "personas", "message_candidates", "sandboxes")
        for (table in tables.filter { it.name in checked }) {
            if (table.name == "sandboxes" && !options.includeSandboxUserFiles || table.name != "sandboxes" && !options.includeAttachments) continue
            stage.resolve(table.archivePath).bufferedReader(Charsets.UTF_8).use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = input.readLine() ?: break // Trusted generated rows are already bounded to 32 MB.
                    val row = Jsonx.json.parseToJsonElement(line).jsonObject
                    when (table.name) {
                        "attachments" -> requireAttachment(row["relativePath"]?.jsonPrimitive?.contentOrNull)
                        "characters", "personas" -> requireAttachment(row["avatarRelativePath"]?.jsonPrimitive?.contentOrNull)
                        "message_candidates" -> {
                            requireAttachment(row["senderAvatar"]?.jsonPrimitive?.contentOrNull)
                            for (column in listOf("imagesJson", "attachmentsJson")) {
                                val references = Jsonx.decode<List<AttachmentRef>>(row[column]!!.jsonPrimitive.content)
                                references.forEach { requireAttachment(it.relativePath) }
                            }
                        }
                        "sandboxes" -> {
                            val id = row["id"]!!.jsonPrimitive.content
                            if (captured["sandboxes/$id"] != "directory") throw StError.Validation("registered sandbox directory is missing from the native snapshot")
                        }
                    }
                }
            }
        }
    }

    suspend fun clearInterruptedStaging() = withContext(Dispatchers.IO) {
        val root = paths.backups.resolve("operations")
        if (!root.exists()) return@withContext
        if (root.canonicalFile != root.absoluteFile) throw StError.Validation("linked backup operation root rejected")
        val stages = root.listFiles() ?: throw StError.Validation("cannot enumerate backup operation staging")
        stages.forEach { stage ->
            if (!stage.name.matches(Regex("[a-f0-9]{32}"))) throw StError.Validation("unexpected backup staging identity")
            deleteStage(stage)
        }
    }
    private fun deleteStage(stage: File) {
        if (!stage.exists()) return
        if (stage.canonicalFile != stage.absoluteFile) throw StError.Validation("linked backup staging rejected")
        val journal = stage.resolve("publication.json")
        if (journal.exists()) {
            if (!OsConstants.S_ISREG(Os.lstat(journal.path).st_mode) || journal.length() > 32_768)
                throw StError.Validation("invalid backup publication journal")
            val entry = Jsonx.json.parseToJsonElement(journal.readText()).jsonObject
            val destination = File(entry["destination"]!!.jsonPrimitive.content)
            val temporary = File(entry["temporary"]!!.jsonPrimitive.content)
            validateDestination(destination)
            if (temporary.parentFile?.canonicalFile != destination.parentFile?.canonicalFile ||
                !temporary.name.startsWith(".st-backup-${stage.name}-") || !temporary.name.endsWith(".tmp") ||
                temporary.canonicalFile != temporary.absoluteFile)
                throw StError.Validation("backup temporary publication target is invalid")
            // Exact owned temporary file only; never remove a completed published archive.
            if (entry["created"]?.jsonPrimitive?.booleanOrNull == true && temporary.exists() && !temporary.delete())
                throw StError.Validation("backup partial archive cleanup failed")
            if (!journal.delete()) throw StError.Validation("backup journal cleanup failed")
        }
        val pending = stage.resolve("publication.pending")
        if (pending.exists() && (!OsConstants.S_ISREG(Os.lstat(pending.path).st_mode) || !pending.delete()))
            throw StError.Validation("backup journal preparation cleanup failed")
        val database = stage.resolve("database")
        if (database.exists()) {
            if (!OsConstants.S_ISDIR(Os.lstat(database.path).st_mode)) throw StError.Validation("invalid backup staging structure")
            database.listFiles()?.forEach { file ->
                if (file.name.removeSuffix(".jsonl") !in NativeDatabaseSnapshot.ALL_TABLES ||
                    !OsConstants.S_ISREG(Os.lstat(file.path).st_mode) || !file.delete()) throw StError.Validation("backup staging cleanup failed")
            }
            if (!database.delete()) throw StError.Validation("backup staging cleanup failed")
        }
        if (!stage.delete()) throw StError.Validation("backup staging cleanup failed")
    }
    private fun writePublicationJournal(stage: File, temporary: File, destination: File, created: Boolean) {
        val text = buildJsonObject {
            put("temporary", temporary.path); put("destination", destination.path); put("created", created)
        }.toString().toByteArray(Charsets.UTF_8)
        if (text.size > 32_768) throw StError.Validation("backup publication paths exceed journal budget")
        val pending = stage.resolve("publication.pending")
        pending.outputStream().use { output -> output.write(text); output.fd.sync() }
        Os.rename(pending.path, stage.resolve("publication.json").path)
    }
    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
    }
    private fun now() = Instant.now().toString()
    companion object {
        const val MAX_BYTES = 8L * 1024 * 1024 * 1024
        private val SANDBOX_DIRECTORY_NAME = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
