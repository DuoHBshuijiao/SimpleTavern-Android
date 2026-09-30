package com.simpletavern.core.data

import android.content.Context
import java.io.File

/**
 * All user files live under app-managed internal storage.
 * Absolute source paths from imports are never used as live handles.
 */
class StoragePaths(context: Context) {
    val root: File = context.filesDir.resolve("st").also { it.mkdirs() }
    val attachments: File = root.resolve("attachments").also { it.mkdirs() }
    val sandboxes: File = root.resolve("sandboxes").also { it.mkdirs() }
    val importStaging: File = root.resolve("import_staging").also { it.mkdirs() }
    val backups: File = root.resolve("backups").also { it.mkdirs() }
    val logs: File = root.resolve("logs").also { it.mkdirs() }
    val credentials: File = root.resolve("credentials").also { it.mkdirs() }

    fun attachmentFile(relativePath: String): File = attachments.resolve(relativePath)
    fun sandboxRoot(sandboxId: String): File = sandboxes.resolve(sandboxId).also { it.mkdirs() }
}
