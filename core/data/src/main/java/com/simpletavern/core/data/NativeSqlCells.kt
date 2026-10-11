package com.simpletavern.core.data

import android.database.Cursor
import com.simpletavern.core.model.StError
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Trusted schema identifiers only. No complete TEXT row enters the Android CursorWindow. */
internal class NativeSqlCells(private val query: (String, Array<Any>) -> Cursor) {
    suspend fun read(table: String, column: String, rowId: Long, byteBudget: Int = 32_000_000): JsonElement {
        currentCoroutineContext().ensureActive()
        val info = query("SELECT typeof(`$column`),length(CAST(`$column` AS BLOB)) FROM `$table` WHERE rowid=?", arrayOf(rowId)).use {
            if (!it.moveToFirst()) throw StError.Conflict("native row disappeared")
            it.getString(0) to it.getLong(1)
        }
        return when (info.first) {
            "null" -> JsonNull
            "integer", "real" -> query("SELECT `$column` FROM `$table` WHERE rowid=?", arrayOf(rowId)).use {
                it.moveToFirst()
                if (info.first == "integer") JsonPrimitive(it.getLong(0)) else {
                    val value = it.getDouble(0)
                    if (!value.isFinite()) throw StError.Validation("nonfinite native number")
                    JsonPrimitive(value)
                }
            }
            "text" -> {
                if (info.second > byteBudget) throw StError.Validation("native TEXT exceeds byte budget")
                val bytes = ByteArrayOutputStream()
                var start = 1L
                while (start <= info.second) {
                    currentCoroutineContext().ensureActive()
                    query("SELECT substr(CAST(`$column` AS BLOB),?,65536) FROM `$table` WHERE rowid=?", arrayOf(start, rowId)).use {
                        it.moveToFirst(); bytes.write(it.getBlob(0))
                    }
                    start += 65_536
                }
                val text = NativeJsonInput.utf8(bytes.toByteArray())
                if (text.codePointCount(0, text.length) > 8_000_000) throw StError.Validation("native TEXT exceeds character budget")
                JsonPrimitive(text)
            }
            else -> throw StError.Unsupported("binary SQLite cells are not supported by native schema v2")
        }
    }
}
