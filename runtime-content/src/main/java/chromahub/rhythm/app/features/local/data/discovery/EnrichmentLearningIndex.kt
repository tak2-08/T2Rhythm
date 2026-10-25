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
        4,
    ), java.io.Closeable {
    private val gson = Gson()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE samples(song TEXT PRIMARY KEY,genre TEXT,vector TEXT,timing TEXT,priority TEXT NOT NULL,signature TEXT NOT NULL,signals TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2)
            db.execSQL("ALTER TABLE samples ADD COLUMN priority TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 3)
            db.execSQL("ALTER TABLE samples ADD COLUMN signature TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 4) db.execSQL("ALTER TABLE samples ADD COLUMN signals TEXT")
    }

    fun put(
        key: String,
        genre: String?,
        vector: List<Double>?,
        timing: TimingTraining?,
        signature: String,
        signals: GenreSignals? = null,
    ) {
        writableDatabase.insertWithOnConflict(
            "samples",
            null,
            ContentValues().apply {
                put("song", key)
                put("signature", signature)
                put("priority", kr.co.metadata.crawler.CatalogFileStore.hash(key))
                put("genre", chromahub.rhythm.app.util.GenreUtils.splitGenres(genre).joinToString(", ").ifEmpty { null })
                put("vector", vector?.let(gson::toJson))
                put("timing", timing?.let(gson::toJson))
                put("signals", signals?.takeIf(GenreSignalFeatures::valid)?.let(gson::toJson))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun updateGenre(song: chromahub.rhythm.app.shared.data.model.Song, genre: String?, vector: List<Double>?, signals: GenreSignals? = null) {
        val values = ContentValues().apply {
            put("genre", chromahub.rhythm.app.util.GenreUtils.splitGenres(genre).joinToString(", ").ifEmpty { null })
            put("signature", EnrichmentStore.signature(song))
            put("vector", vector?.let(gson::toJson))
            if (signals != null) put("signals", gson.toJson(signals))
        }
        if (writableDatabase.update("samples", values, "song=?", arrayOf(song.uri.toString())) == 0)
            put(song.uri.toString(), genre, vector, null, EnrichmentStore.signature(song), signals)
    }

    fun signals(key: String, signature: String): GenreSignals? = readableDatabase.rawQuery(
        "SELECT signals FROM samples WHERE song=? AND signature=?", arrayOf(key, signature)).use { c ->
        if (!c.moveToFirst() || c.isNull(0)) null else parseSignals(c.getString(0))
    }
    private fun parseSignals(json: String?): GenreSignals? = runCatching {
        gson.fromJson(json, GenreSignals::class.java)?.takeIf(GenreSignalFeatures::valid)
    }.getOrNull()

    /** A removed or replaced recording cannot keep voting through a disposable cache. */
    fun reconcile(songs: List<chromahub.rhythm.app.shared.data.model.Song>, checkpoint: () -> Unit = {}) {
        val signatures = songs.associate { it.uri.toString() to EnrichmentStore.signature(it) }
        val stale = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT song,signature FROM samples", null).use { c ->
            while (c.moveToNext()) {
                checkpoint()
                if (signatures[c.getString(0)] != c.getString(1)) stale += c.getString(0)
            }
        }
        writableDatabase.beginTransaction()
        try {
            stale.forEach { key ->
                checkpoint()
                writableDatabase.delete("samples", "song=?", arrayOf(key))
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun model(feedback: List<EnrichmentProposal>, checkpoint: () -> Unit = {}, reviewed: List<TimingTraining> = emptyList(),
              genreReviews: List<GenreExample> = emptyList(), correctedKeys: Set<String> = genreReviews.map { it.key }.toSet()): EnrichmentModel {
        // Rebuildable cache: keep a fixed representative reservoir, with no duplicate recording votes.
        writableDatabase.execSQL("UPDATE samples SET timing=NULL WHERE song NOT IN (SELECT song FROM samples WHERE timing IS NOT NULL ORDER BY priority LIMIT 256)")
        writableDatabase.execSQL("DELETE FROM samples WHERE song NOT IN (SELECT song FROM samples ORDER BY priority LIMIT 8192)")
        var timingBytes = 0L
        val trim = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT song,LENGTH(CAST(timing AS BLOB)) FROM samples WHERE timing IS NOT NULL ORDER BY priority", null).use { c ->
            while (c.moveToNext()) { timingBytes += c.getLong(1); if (timingBytes > 8_000_000) trim += c.getString(0) }
        }
        trim.forEach { writableDatabase.execSQL("UPDATE samples SET timing=NULL WHERE song=?", arrayOf(it)) }
        var signalBytes = 0L
        val trimSignals = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT song,LENGTH(CAST(signals AS BLOB)) FROM samples WHERE signals IS NOT NULL ORDER BY priority", null).use { c ->
            while (c.moveToNext()) { checkpoint(); signalBytes += c.getLong(1); if (signalBytes > 12_000_000) trimSignals += c.getString(0) }
        }
        trimSignals.forEach { writableDatabase.execSQL("UPDATE samples SET signals=NULL WHERE song=?", arrayOf(it)) }
        val genres = mutableListOf<GenreExample>()
        val timings = mutableListOf<TimingTraining>()
        val counts = mutableMapOf<String, Int>()
        val timingCounts = mutableMapOf<String, Int>()
        readableDatabase
            .rawQuery("SELECT song,genre,vector,timing,signals FROM samples ORDER BY priority", null)
            .use { c ->
                while (c.moveToNext()) {
                    checkpoint()
                    val label = c.getString(1)
                    if (
                        chromahub.rhythm.app.util.GenreUtils.isKnownGenre(label) &&
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
                            genres += GenreExample(c.getString(0), label, vector, signals = if (c.isNull(4)) null else parseSignals(c.getString(4)))
                            counts[label] = (counts[label] ?: 0) + 1
                        }
                    }
                    if (!c.isNull(3) && timings.size < 128)
                        runCatching { gson.fromJson(c.getString(3), TimingTraining::class.java) }
                            .getOrNull()
                            ?.let {
                                if ((timingCounts[it.genre] ?: 0) < 24) {
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
                        "SELECT vector,signals FROM samples WHERE song=? AND signature=?",
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
                                ?.let { genres += GenreExample(p.songKey, p.value, it, false, if (c.isNull(1)) null else parseSignals(c.getString(1))) }
                    }
            }
        return EnrichmentModel(examples = GenreTrainingPolicy.merge(genres.filterNot { it.key in correctedKeys }, genreReviews),
            timings = (reviewed + timings).distinctBy { it.pcm }.take(192))
    }
}
