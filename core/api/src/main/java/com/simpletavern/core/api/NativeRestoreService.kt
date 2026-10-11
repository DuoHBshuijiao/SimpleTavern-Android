package com.simpletavern.core.api

import android.system.Os
import android.system.OsConstants
import com.simpletavern.core.data.*
import com.simpletavern.core.data.db.AppDatabase
import com.simpletavern.core.model.*
import java.io.File
import java.time.Instant
import java.util.zip.ZipFile
import kotlinx.coroutines.*

/** Additive by default. Explicit replacement retains durable before-images and never prunes unrelated data. */
class NativeRestoreService(private val db: AppDatabase, private val paths: StoragePaths,
                           private val tasks: TaskStore, private val gate: DataOperationGate,
                           private val credentials: CredentialStore) {
    private val root get() = paths.backups.resolve("restores")
    private val inspector = NativeRestoreInspectionService(db, paths, tasks, gate, credentials)
    private val commits = NativeRestoreCommitStore(db)

    suspend fun restore(taskId: String, archive: File, password: CharArray?,
                        mode: NativeRestoreMode = NativeRestoreMode.ADD_MISSING,
                        progress: suspend (NativeRestoreProgress, Boolean) -> Unit): NativeRestoreProgress =
        restoreInput(taskId, password, mode, { target, update ->
            inspector.copyArchive(archive, target); update(target.length())
        }, progress)

    internal suspend fun restore(taskId: String, archive: SourceArchiveInput, inputs: SourceArchiveInputs,
        password: CharArray?, mode: NativeRestoreMode,
        progress: suspend (NativeRestoreProgress, Boolean) -> Unit): NativeRestoreProgress =
        restoreInput(taskId, password, mode, { target, update ->
            try { update(inputs.copyTo(archive, target, update)) } finally { archive.close() }
        }, progress)

    internal suspend fun inspect(archive: SourceArchiveInput, inputs: SourceArchiveInputs, password: CharArray?,
        mode: NativeRestoreMode): NativeRestoreInspection = inspector.inspect(archive, inputs, password, mode)

    internal suspend fun preflight(archive: SourceArchiveInput, inputs: SourceArchiveInputs): NativeBackupPreflight =
        inspector.preflight(archive, inputs)

    private suspend fun restoreInput(taskId: String, password: CharArray?, mode: NativeRestoreMode,
        copyInput: suspend (File, suspend (Long) -> Unit) -> Unit,
        progress: suspend (NativeRestoreProgress, Boolean) -> Unit): NativeRestoreProgress = withContext(Dispatchers.IO) {
        if (!taskId.matches(Regex("[a-f0-9]{32}"))) throw StError.Validation("invalid restore identity")
        val lease = gate.exclusiveLease(taskId)
        val stage = root.resolve(taskId)
        val ledger = NativeRestoreFileLedger(paths, stage, inspector)
        var owned = false
        var state = NativeRestoreProgress(taskId, "input", now(), mode = mode)
        try {
            if (tasks.nonTerminal().any { it.id != taskId }) throw StError.Conflict("active tasks prevent native restore")
            val task = tasks.get(taskId) ?: throw StError.NotFound("restore task")
            if (task.kind != TaskKind.RESTORE || task.status != TaskStatus.RUNNING) throw StError.Conflict("restore task is not running")
            if (paths.backups.canonicalFile != paths.backups.absoluteFile || root.canonicalFile != root.absoluteFile)
                throw StError.Validation("linked restore staging root")
            if (!stage.mkdirs()) throw StError.Conflict("restore staging already exists or unavailable")
            owned = true
            progress(state, true)
            val input = stage.resolve("archive.zip")
            copyInput(input) { bytes ->
                state = state.copy(inputBytes = bytes, updatedAt = now()); progress(state, true)
            }
            val manifest = NativeBackupPreflightService(db).inspect(input).manifest
            state = state.copy(phase = "inspection", updatedAt = now()); progress(state, true)
            NativeDatabaseRestorePlan.prepare(db, input, manifest, stage.resolve("rows.db")).use { rows ->
                val checked = inspector.inspectPrepared(input, manifest, rows, password, state.updatedAt, mode)
                state = state.copy(inspection = checked, updatedAt = now()); progress(state, true)
                if (checked.blockingIssues != 0L) throw StError.Conflict("native restore has ${checked.blockingIssues} blocking issues; original data kept")
                // Check current-task/commit-marker collisions BEFORE any live publication, not only at DB commit.
                for (table in manifest.tables.filter { it.name in setOf("tasks", "import_batches") }) rows.forEachRow(table) { _, row ->
                    if (row["id"]?.toString() == "\"$taskId\"") throw StError.Conflict("native archive collides with restore task identity")
                }
                if (commits.committed(taskId)) throw StError.Conflict("restore identity already committed")
                if (credentials.pendingRestoreId() != null) throw StError.Conflict("credential recovery is pending")
                state = state.copy(phase = "staging_files", updatedAt = now()); progress(state, true)
                var lastCheckpoint = 0L
                ledger.prepare(input, manifest, mode) { files, bytes ->
                    val time = System.nanoTime() / 1_000_000
                    if (time - lastCheckpoint >= 500) {
                        state = state.copy(files = files, bytes = bytes, updatedAt = now()); progress(state, true)
                        lastCheckpoint = time
                    }
                }
                state = state.copy(phase = "publishing_files", bytes = ledger.preparedBytes, updatedAt = now()); progress(state, true)
                ledger.publish { files ->
                    state = state.copy(files = files, updatedAt = now())
                    val time = System.nanoTime() / 1_000_000
                    progress(state, time - lastCheckpoint >= 500)
                    if (time - lastCheckpoint >= 500) lastCheckpoint = time
                }
                if (manifest.credentialsIncluded) {
                    state = state.copy(phase = "credentials", updatedAt = now()); progress(state, true)
                    val restored = ZipFile(input).use { zip ->
                        zip.getInputStream(zip.getEntry(manifest.credentialArchivePath!!)).use {
                            CredentialArchiveCipher.decrypt(NativeJsonInput.read(it, 3_000_000), password!!)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        credentials.prepareRestore(taskId, restored, mode == NativeRestoreMode.REPLACE_INCLUDED)
                        credentials.applyRestore(taskId)
                    }
                }
                // Re-check reused data/files/credentials AFTER publication but before committing the database.
                val finalInspection = inspector.inspectPrepared(input, manifest, rows, password, checked.inspectedAt, mode, requirePublishedAssets = true)
                if (finalInspection.blockingIssues != 0L) throw StError.Conflict("native restore premises changed; undo required")
                state = state.copy(phase = "database", files = ledger.publishedFiles, updatedAt = now()); progress(state, true)
                rows.commit(taskId, mode) { applied ->
                    state = state.copy(phase = "done", rows = applied, committed = true, updatedAt = now())
                    val current = tasks.get(taskId) ?: throw StError.NotFound("restore task")
                    if (current.status == TaskStatus.STOPPING || current.status.isTerminal) throw CancellationException("restore stopped before commit")
                    tasks.upsert(current.copy(status = TaskStatus.SUCCEEDED, terminalReason = TaskTerminalReason.COMPLETED,
                        errorMessage = null, resultSummary = "native restore committed", updatedAt = state.updatedAt), Jsonx.encode(state))
                }
                progress(state, false) // Task success/progress is already part of the durable Room commit.
            }
            state
        } finally {
            try {
                withContext(NonCancellable) {
                    if (owned) resolveStage(stage, ledger)
                }
            } finally { lease.close() }
        }
    }

    /** Commit is looked up AFTER cancellation/exception too; an in-memory flag is not the crash authority. */
    private suspend fun resolveStage(stage: File, ledger: NativeRestoreFileLedger) {
        val committed = try { commits.committed(stage.name) }
        catch (error: Throwable) { gate.requireRecovery(); throw error }
        var failure: Throwable? = null
        if (!committed) try { ledger.rollback() } catch (error: Throwable) { failure = error }
        try {
            if (credentials.pendingRestoreId() == stage.name) credentials.resolveRestore(stage.name, committed)
        } catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
        if (failure != null) {
            if (!committed) gate.requireRecovery()
            throw StError.Conflict("native restore recovery needs attention; journal retained")
        }
        try { ledger.cleanupStage() }
        catch (error: Throwable) { if (!committed) gate.requireRecovery(); throw error }
    }

    suspend fun recoverInterrupted() = withContext(Dispatchers.IO) {
        if (root.exists()) {
            if (root.canonicalFile != root.absoluteFile || !OsConstants.S_ISDIR(Os.lstat(root.path).st_mode)) throw StError.Validation("invalid restore recovery root")
            val stages = root.listFiles() ?: throw StError.Validation("restore recovery staging unavailable")
            for (stage in stages) {
                if (!stage.name.matches(Regex("[a-f0-9]{32}")) || stage.canonicalFile != stage.absoluteFile || !OsConstants.S_ISDIR(Os.lstat(stage.path).st_mode))
                    throw StError.Validation("invalid restore recovery identity")
                resolveStage(stage, NativeRestoreFileLedger(paths, stage, inspector))
            }
        }
        credentials.pendingRestoreId()?.let { credentials.resolveRestore(it, commits.committed(it)) }
        // No worker/tool/network/import replay. General process recovery interrupts uncommitted task rows.
    }
    private fun now() = Instant.now().toString()
}
