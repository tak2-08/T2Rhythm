// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson

internal class EnrichmentLearningIndex(context: Context) :
    SQLiteOpenHelper(
        context,
        java.io.File(context.cacheDir, "enrichment-learning.db").path,
        null,
        3,
    ) {
    private val gson = Gson()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE samples(song TEXT PRIMARY KEY,genre TEXT,vector TEXT,timing TEXT,priority TEXT NOT NULL,signature TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2)
            db.execSQL("ALTER TABLE samples ADD COLUMN priority TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 3)
            db.execSQL("ALTER TABLE samples ADD COLUMN signature TEXT NOT NULL DEFAULT ''")
    }

    fun put(
        key: String,
        genre: String?,
        vector: List<Double>?,
        timing: TimingTraining?,
        signature: String,
    ) {
        writableDatabase.insertWithOnConflict(
            "samples",
            null,
            ContentValues().apply {
                put("song", key)
                put("signature", signature)
                put("priority", kr.co.metadata.crawler.CatalogFileStore.hash(key))
                put("genre", genre)
                put("vector", vector?.let(gson::toJson))
                put("timing", timing?.let(gson::toJson))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun model(feedback: List<EnrichmentProposal>, checkpoint: () -> Unit = {}): EnrichmentModel {
        val genres = mutableListOf<GenreExample>()
        val timings = mutableListOf<TimingTraining>()
        val counts = mutableMapOf<String, Int>()
        val timingCounts = mutableMapOf<String, Int>()
        readableDatabase
            .rawQuery("SELECT song,genre,vector,timing FROM samples ORDER BY priority", null)
            .use { c ->
                while (c.moveToNext()) {
                    checkpoint()
                    val label = c.getString(1)
                    if (
                        !label.isNullOrBlank() &&
                            !c.isNull(2) &&
                            genres.size < 4096 &&
                            (counts[label] ?: 0) < 64
                    ) {
                        val vector =
                            runCatching {
                                    gson
                                        .fromJson(c.getString(2), Array<Double>::class.java)
                                        .toList()
                                }
                                .getOrNull()
                        if (vector != null) {
                            genres += GenreExample(c.getString(0), label, vector)
                            counts[label] = (counts[label] ?: 0) + 1
                        }
                    }
                    if (!c.isNull(3) && timings.size < 32)
                        runCatching { gson.fromJson(c.getString(3), TimingTraining::class.java) }
                            .getOrNull()
                            ?.let {
                                if ((timingCounts[it.genre] ?: 0) < 8) {
                                    timings += it
                                    timingCounts[it.genre] = (timingCounts[it.genre] ?: 0) + 1
                                }
                            }
                }
            }
        feedback
            .filter { it.kind == "GENRE" && it.state == "REJECTED" }
            .take(512)
            .forEach { p ->
                readableDatabase
                    .rawQuery(
                        "SELECT vector FROM samples WHERE song=? AND signature=?",
                        arrayOf(p.songKey, p.sourceSignature),
                    )
                    .use { c ->
                        if (c.moveToFirst() && !c.isNull(0))
                            runCatching {
                                    gson
                                        .fromJson(c.getString(0), Array<Double>::class.java)
                                        .toList()
                                }
                                .getOrNull()
                                ?.let { genres += GenreExample(p.songKey, p.value, it, false) }
                    }
            }
        return EnrichmentModel(examples = genres, timings = timings)
    }
}
