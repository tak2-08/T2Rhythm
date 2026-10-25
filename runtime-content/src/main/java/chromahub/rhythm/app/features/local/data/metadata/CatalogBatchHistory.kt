// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.metadata

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.app.shared.data.model.Song
import kr.co.metadata.crawler.CatalogFileStore
import org.json.JSONArray

/** Completion is tied to the requested fields and input, not just the last queue position. */
class CatalogBatchHistory(private val context: Context) :
    SQLiteOpenHelper(context, "catalog_batch_history.db", null, 1), java.io.Closeable {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE completed (song_key TEXT PRIMARY KEY, policy TEXT NOT NULL, before_input TEXT NOT NULL, after_input TEXT NOT NULL, checked_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun policy(options: CatalogOptions): String =
        CatalogFileStore.hash(
            options
                .copy(
                    wifiOnly = false,
                    exportTree = null,
                    providers = options.providers.sortedBy { it.name }.toSet(),
                    forceFields = options.forceFields.sortedBy { it.name }.toSet(),
                )
                .toString() + ":" + AppSettings.getInstance(context).lyricsSourcePreference.value
        )

    private fun input(song: Song): String =
        CatalogFileStore.hash(
            JSONArray(
                    listOf(
                        song.uri.toString(),
                        song.title,
                        song.artist,
                        song.album,
                        song.albumArtist,
                        song.genre,
                        song.year,
                        song.duration,
                        song.artworkUri?.toString()?.substringBefore('#'),
                    )
                )
                .toString()
        )

    fun pending(songs: List<Song>, options: CatalogOptions): List<Song> {
        val catalog = CatalogEnrichmentService.get(context)
        val policy = policy(options)
        return songs.filter { song ->
            val fingerprint = input(song)
            readableDatabase
                .rawQuery(
                    "SELECT before_input,after_input FROM completed WHERE song_key=? AND policy=?",
                    arrayOf(catalog.songKey(song), policy),
                )
                .use {
                    !it.moveToFirst() ||
                        fingerprint != it.getString(0) && fingerprint != it.getString(1)
                }
        }
    }

    fun complete(before: Song, after: Song, options: CatalogOptions) {
        writableDatabase.insertWithOnConflict(
            "completed",
            null,
            ContentValues().apply {
                put("song_key", CatalogEnrichmentService.get(context).songKey(before))
                put("policy", policy(options))
                put("before_input", input(before))
                put("after_input", input(after))
                put("checked_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Legacy manifests cannot identify individual failures; migrate only unambiguous runs. */
    fun migrateLegacy(songs: List<Song>, options: CatalogOptions) {
        if (
            readableDatabase.rawQuery("SELECT 1 FROM state WHERE key='legacy'", null).use {
                it.moveToFirst()
            }
        )
            return
        val plan = CatalogBatchPlan.read(context)
        writableDatabase.beginTransaction()
        try {
            if (plan != null && plan.failed == 0 && plan.next > 0 && plan.policy == null) {
                val done = plan.keys.take(plan.next).toHashSet()
                val catalog = CatalogEnrichmentService.get(context)
                songs.filter { catalog.songKey(it) in done }.forEach { complete(it, it, options) }
            }
            writableDatabase.execSQL("INSERT INTO state VALUES ('legacy','1')")
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }
}
