package com.simpletavern.core.data

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.simpletavern.core.model.StError
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Secrets never enter Room configuration, import snapshots, or normal backups. */
class CredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Mutex()
    private fun encryptedPreferences(name: String) =
        EncryptedSharedPreferences.create(
            appContext, name,
            MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    private val prefs by lazy { encryptedPreferences("st_credentials") }
    private val undoPrefs by lazy { encryptedPreferences("st_restore_undo") }
    private val undoJson = Json(Jsonx.json) { explicitNulls = true }

    suspend fun putSecret(id: String, secret: String) = withContext(Dispatchers.IO) {
        require(id.isNotBlank())
        lock.withLock {
            if (!prefs.edit().putString(id, secret).commit()) throw StError.Validation("credential save failed")
        }
    }

    suspend fun getSecret(id: String): String? = withContext(Dispatchers.IO) {
        requireOrdinaryCredential(id)
        lock.withLock { prefs.getString(id, null) }
    }

    /** Structured login records must not be sent as ordinary API keys by chat, TTS or search. */
    suspend fun getOAuthSecret(id: String): String? = withContext(Dispatchers.IO) {
        if (!id.startsWith(OAUTH_PREFIX)) throw StError.Validation("invalid OAuth credential reference")
        lock.withLock { prefs.getString(id, null) }
    }

    suspend fun hasSecret(id: String) = !getSecret(id).isNullOrBlank()

    /** One consistent snapshot of only this connection's credentials, not the entire store. */
    suspend fun getSecrets(ids: Set<String>, allowAws: Boolean = false): Map<String, String> = withContext(Dispatchers.IO) {
        ids.forEach { if (!allowAws || !it.startsWith(AWS_PREFIX)) requireOrdinaryCredential(it) }
        if (ids.size > 65 || ids.any { it.isBlank() || it.length > 1024 || it.any(Char::isISOControl) })
            throw StError.Validation("invalid selected credential references")
        lock.withLock { ids.mapNotNull { id -> prefs.getString(id, null)?.let { id to it } }.toMap() }
    }

    /** Native password-encrypted export only. Callers must never store this map in task/log JSON. */
    suspend fun snapshotSecrets(): Map<String, String> = withContext(Dispatchers.IO) {
        lock.withLock { prefs.all.mapValues { (_, value) -> value as? String
            ?: throw StError.Validation("unsupported credential value") } }
    }

    suspend fun remove(id: String, allowAws: Boolean = false) = withContext(Dispatchers.IO) {
        if (id.startsWith(AWS_PREFIX) && !allowAws) throw StError.Validation("AWS records require the Bedrock credential adapter")
        lock.withLock {
            if (!prefs.edit().remove(id).commit()) throw StError.Validation("credential removal failed")
        }
    }

    private fun requireOrdinaryCredential(id: String) {
        if (id.startsWith(OAUTH_PREFIX)) throw StError.Validation("OAuth records require the provider login adapter")
        if (id.startsWith(AWS_PREFIX)) throw StError.Validation("AWS records require the Bedrock credential adapter")
    }

    companion object {
        const val OAUTH_PREFIX = "oauth:"
        const val AWS_PREFIX = "aws:bedrock:"
    }

    /** Encrypted, durable undo BEFORE any live preferences write. Never copied into a ZIP or Room. */
    suspend fun prepareRestore(taskId: String, incoming: Map<String, String>, replaceIncluded: Boolean = false): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            validateRestoreId(taskId)
            if (incoming.size > 20_000) throw StError.Validation("too many restored credentials")
            if (undoPrefs.contains("record")) throw StError.Conflict("credential restore recovery is pending")
            val before = linkedMapOf<String, String?>()
            val after = linkedMapOf<String, String>()
            incoming.forEach { (id, value) ->
                if (id.isBlank() || id.length > 4096 || id.contains('\u0000')) throw StError.Validation("invalid restored credential identity")
                val current = prefs.getString(id, null)
                if (!replaceIncluded && current != null && current != value) throw StError.Conflict("credential restore conflict")
                if (current != value) { before[id] = current; after[id] = value }
            }
            val record = undoJson.encodeToString(CredentialRestoreUndo(taskId, before, after))
            if (record.toByteArray(Charsets.UTF_8).size > 4_500_000) throw StError.Validation("credential undo exceeds budget")
            if (!undoPrefs.edit().putString("record", record).commit()) throw StError.Validation("encrypted credential undo save failed")
            syncPreferenceDirectory()
            after.size
        }
    }

    suspend fun applyRestore(taskId: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val undo = readUndo() ?: throw StError.Validation("credential undo missing")
            if (undo.taskId != taskId) throw StError.Conflict("credential undo belongs to another restore")
            undo.before.forEach { (id, old) -> if (prefs.getString(id, null) != old) throw StError.Conflict("credentials changed before restore") }
            val editor = prefs.edit()
            undo.after.forEach { (id, value) -> editor.putString(id, value) }
            if (!editor.commit()) throw StError.Validation("credential restore write failed")
            syncPreferenceDirectory()
        }
    }

    suspend fun pendingRestoreId(): String? = withContext(Dispatchers.IO) { lock.withLock { readUndo()?.taskId } }

    /** Roll back only values matching this operation's before/after images; do not erase unrelated writes. */
    suspend fun resolveRestore(taskId: String, committed: Boolean) = withContext(Dispatchers.IO) {
        lock.withLock {
            val undo = readUndo() ?: return@withLock
            if (undo.taskId != taskId) throw StError.Conflict("credential undo belongs to another restore")
            if (!committed) {
                val editor = prefs.edit()
                undo.before.forEach { (id, old) ->
                    val current = prefs.getString(id, null)
                    if (current != old && current != undo.after[id]) throw StError.Conflict("credential rollback found an unrelated change")
                    if (old == null) editor.remove(id) else editor.putString(id, old)
                }
                if (!editor.commit()) throw StError.Validation("credential rollback failed")
                syncPreferenceDirectory()
            }
            if (!undoPrefs.edit().remove("record").commit()) throw StError.Validation("encrypted credential undo cleanup failed")
            syncPreferenceDirectory()
        }
    }
    private fun readUndo(): CredentialRestoreUndo? = undoPrefs.getString("record", null)?.let { text ->
        if (text.toByteArray(Charsets.UTF_8).size > 4_500_000) throw StError.Validation("credential undo exceeds budget")
        NativeJsonInput.parse(text)
        val undo = Jsonx.decode<CredentialRestoreUndo>(text)
        validateRestoreId(undo.taskId)
        if (undo.before.keys != undo.after.keys || undo.after.size > 20_000) throw StError.Validation("invalid credential undo")
        undo
    }
    private fun validateRestoreId(id: String) {
        if (!id.matches(Regex("[a-f0-9]{32}"))) throw StError.Validation("invalid credential restore identity")
    }
    private fun syncPreferenceDirectory() {
        val directory = appContext.dataDir.resolve("shared_prefs")
        val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw StError.Validation("credential preferences root is not a directory")
            Os.fsync(fd)
        } finally { Os.close(fd) }
    }
}

@Serializable
private data class CredentialRestoreUndo(val taskId: String, val before: Map<String, String?>, val after: Map<String, String>)
