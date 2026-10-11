package com.simpletavern.core.data

import com.simpletavern.core.data.db.AppDatabase
import com.simpletavern.core.model.StError

/** Authoritative crash decision, never infer commit from a file journal or an in-memory boolean. */
class NativeRestoreCommitStore(private val db: AppDatabase) {
    suspend fun committed(id: String): Boolean {
        val marker = db.imports().getBatch(id) ?: return false
        if (marker.source != "native_restore" || marker.status != "done") throw StError.Conflict("restore marker identity belongs to another operation")
        return true
    }
}
