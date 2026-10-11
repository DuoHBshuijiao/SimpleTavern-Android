package com.simpletavern.core.data

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.simpletavern.core.model.StError
import java.io.File

/**
 * All user files live under app-managed internal storage.
 * Absolute source paths from imports are never used as live handles.
 */
class StoragePaths(context: Context) {
    // Resolve only the framework-provided app data prefix (e.g. Android's /data/user/0 alias).
    // Never canonicalize an existing user-controlled managed root and thereby bless a redirected link.
    private val appData = context.dataDir.canonicalFile
    private val appFiles = context.filesDir.let { declared ->
        if (declared.parentFile?.canonicalFile != appData) throw StError.Validation("framework files directory is outside app data")
        val file = appData.resolve(declared.name)
        if (!OsConstants.S_ISDIR(Os.lstat(file.path).st_mode)) throw StError.Validation("framework files directory is linked or unavailable")
        file
    }
    val root: File = directory(appFiles, "st")
    val attachments: File = directory(root, "attachments")
    val sandboxes: File = directory(root, "sandboxes")
    val importStaging: File = directory(root, "import_staging")
    val importInputs: File = directory(root, "import_inputs")
    val copyInputs: File = directory(root, "copy_inputs")
    val speechStaging: File = directory(root, "speech_staging")
    val backups: File = directory(root, "backups")
    val logs: File = directory(root, "logs")
    val credentials: File = directory(root, "credentials")

    private fun directory(parent: File, name: String): File {
        val file = parent.resolve(name)
        val existing = try { Os.lstat(file.path) }
            catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) null else throw StError.Validation("managed storage unavailable") }
        if (existing == null) {
            if (!file.mkdir()) throw StError.Validation("cannot create managed storage")
        } else if (!OsConstants.S_ISDIR(existing.st_mode)) throw StError.Validation("managed storage root is linked or not a directory")
        if (file.canonicalFile != file.absoluteFile) throw StError.Validation("managed storage root is redirected")
        return file
    }

    fun attachmentFile(relativePath: String): File = attachments.resolve(relativePath)
    fun sandboxRoot(sandboxId: String): File {
        require(sandboxId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "invalid sandbox id" }
        return sandboxes.resolve(sandboxId)
    }
}
