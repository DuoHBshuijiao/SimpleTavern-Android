package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
data class NativeBackupColumn(val name: String, val type: String, val notNull: Boolean, val primaryKey: Int)

@Serializable
data class NativeBackupTable(val name: String, val columns: List<NativeBackupColumn>, val rows: Long,
    val archivePath: String)

@Serializable
data class NativeBackupFile(val path: String, val kind: String, val size: Long = 0,
    val mode: Int? = null, val linkTarget: String? = null)

@Serializable
data class NativeBackupManifest(
    val format: String = "simpletavern-native", val version: Int = 1, val schemaVersion: Int = 2,
    val createdAt: String, val options: BackupOptions, val tables: List<NativeBackupTable>,
    val files: List<NativeBackupFile>, val omittedTables: List<String>,
    val credentialsIncluded: Boolean, val credentialArchivePath: String? = null,
    val environmentJson: String, val notes: List<String>,
)

@Serializable
data class BackupTaskProgress(val taskId: String, val phase: String, val updatedAt: String,
    val rows: Long = 0, val files: Int = 0, val bytes: Long = 0,
    val outputPath: String? = null, val error: String? = null,
    val outputUri: String? = null, val documentBytes: Long = 0,
    val documentMayBePartial: Boolean = false, val documentWriteCompleted: Boolean = false,
    val privateArchiveRetained: Boolean = false)

@Serializable
data class NativeBackupPreflight(val manifest: NativeBackupManifest, val uncompressedBytes: Long,
    val notes: List<String>)

/** Read-only inspection, not a reusable restore token or a claim that a restore has committed. */
@Serializable
data class NativeRestoreIssue(val code: String, val table: String? = null, val keyJson: String? = null,
    val path: String? = null, val column: String? = null, val blocking: Boolean = true)

@Serializable
data class NativeRestoreTablePlan(val table: String, val rows: Long, val insertRows: Long,
    val identicalRows: Long, val conflictingRows: Long)

/** Native restore only; source imports always retain their merge/coexist policy. */
@Serializable
enum class NativeRestoreMode { ADD_MISSING, REPLACE_INCLUDED }

@Serializable
data class NativeRestoreInspection(
    val inspectedAt: String, val tables: List<NativeRestoreTablePlan>,
    val newFiles: Int, val identicalFiles: Int, val conflictingFiles: Int,
    val interruptedTasks: Long, val expiredApprovals: Long, val interruptedSummaryJobs: Long,
    val credentialsChecked: Boolean, val newCredentials: Int, val identicalCredentials: Int,
    val conflictingCredentials: Int, val blockingIssues: Long, val warningIssues: Long,
    val issues: List<NativeRestoreIssue>, val issuesTruncated: Boolean, val notes: List<String>,
    val mode: NativeRestoreMode = NativeRestoreMode.ADD_MISSING,
)

@Serializable
data class NativeRestoreProgress(val taskId: String, val phase: String, val updatedAt: String,
    val rows: Long = 0, val files: Int = 0, val bytes: Long = 0, val committed: Boolean = false,
    val inspection: NativeRestoreInspection? = null, val error: String? = null,
    val mode: NativeRestoreMode = NativeRestoreMode.ADD_MISSING, val inputBytes: Long = 0)
