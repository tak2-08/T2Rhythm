// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.metadata

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Disposable copy receipts, not backup content. Never rewrite a growing preferences XML per file.
 */
internal class ArchiveSyncIndex(context: Context) :
    SQLiteOpenHelper(context, "archive_sync.db", null, 1) {
    private var batch = 0

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE copies(key TEXT PRIMARY KEY,signature TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun migrate(prefs: SharedPreferences) {
        if (prefs.getBoolean("sync_index_v1_migrated", false)) return
        val old = prefs.all.filterKeys { it.startsWith("synced:") }
        val db = writableDatabase
        db.beginTransaction()
        try {
            old.forEach { (key, value) -> if (value is String) put(key, value) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // Copy first, then remove only derived receipts in one atomic preferences commit.
        val editor = prefs.edit()
        old.keys.forEach { editor.remove(it) }
        check(editor.putBoolean("sync_index_v1_migrated", true).commit())
    }

    fun signature(key: String): String? =
        readableDatabase.rawQuery("SELECT signature FROM copies WHERE key=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun put(key: String, signature: String) {
        writableDatabase.insertWithOnConflict(
            "copies",
            null,
            ContentValues().apply {
                put("key", key)
                put("signature", signature)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun remember(key: String, signature: String) {
        if (batch == 0) writableDatabase.beginTransactionNonExclusive()
        put(key, signature)
        if (++batch >= 32) checkpoint()
    }

    fun checkpoint() {
        if (batch == 0) return
        try {
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
            batch = 0
        }
    }

    override fun close() {
        try {
            checkpoint()
        } finally {
            super.close()
        }
    }
}
