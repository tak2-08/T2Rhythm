// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.annotation.Keep
import chromahub.rhythm.app.features.local.data.metadata.PersonalArchive
import com.google.gson.Gson
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Keep
data class ListeningRecord(
    val id: String,
    val playId: String,
    val kind: String,
    val songId: String,
    val songKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String?,
    val startedAt: Long,
    val endedAt: Long,
    val listenedMs: Long,
    val audioDurationMs: Long,
    val startPositionMs: Long,
    val endPositionMs: Long,
    val speed: Float,
    val reason: String,
    val completed: Boolean = false,
    val libraryAddedAt: Long = 0,
    val timezone: String = ZoneId.systemDefault().id,
    val outputTypes: List<Int> = emptyList(),
    val repeatMode: Int = 0,
    val shuffled: Boolean = false,
    val volume: Float = 1f,
    val favorite: Boolean = false,
    val recordedAt: Long = System.currentTimeMillis(),
    val albumArtist: String? = null,
    val libraryModifiedAt: Long = 0,
    val codec: String? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val bitrate: Int? = null,
    val year: Int = 0,
    val trackNumber: Int = 0,
    val discNumber: Int = 1,
    val situation: ListeningSituation? = null,
    val playedAudioMs: Long? = null,
    val playCompletionRatio: Double? = null,
    val immediateRepeat: Boolean = false,
    val omittedEvents: Int = 0,
)

/** Indexed on-device source of truth. Raw journals are retained forever and can rebuild the DB. */
class ListeningLedger private constructor(private val context: Context) :
    SQLiteOpenHelper(context, "listening_ledger.db", null, 3) {
    private val gson = Gson()
    private val lock = Any()
    @Volatile private var recovered = false
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    private val issue = MutableStateFlow<String?>(null)
    val storageIssue = issue.asStateFlow()

    fun storageIssue(value: String?) {
        issue.value = value
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE records (id TEXT PRIMARY KEY, play_id TEXT NOT NULL, kind TEXT NOT NULL, song_key TEXT NOT NULL, song_id TEXT NOT NULL, started INTEGER NOT NULL, ended INTEGER NOT NULL, listened INTEGER NOT NULL, artist TEXT NOT NULL, genre TEXT, completed INTEGER NOT NULL, recorded INTEGER NOT NULL, payload TEXT NOT NULL, title TEXT, album TEXT)"
        )
        db.execSQL("CREATE INDEX records_time ON records(kind, started, ended)")
        db.execSQL("CREATE INDEX records_song ON records(song_key, kind, started)")
        db.execSQL("CREATE INDEX records_play ON records(play_id)")
        db.execSQL("CREATE INDEX records_ended ON records(kind,ended,song_id)")
        db.execSQL(
            "CREATE TABLE features (song_key TEXT PRIMARY KEY, signature TEXT NOT NULL, payload TEXT NOT NULL)"
        )
        db.execSQL("CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL(
            "INSERT INTO state VALUES ('history_generation',?)",
            arrayOf(java.util.UUID.randomUUID().toString()),
        )
        affinityTables(db)
        db.execSQL(
            "CREATE TABLE completed_songs(song_key TEXT PRIMARY KEY,first_completed INTEGER NOT NULL)"
        )
        recentTables(db)
        db.execSQL("INSERT INTO state VALUES ('recent_index_ready','1')")
        db.execSQL("INSERT INTO state VALUES ('compact_stats_ready','1')")
        db.execSQL("INSERT INTO state(key,value) VALUES ('affinity_index_ready','1')")
    }

    private fun affinityTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS affinity_records (id TEXT PRIMARY KEY, listened INTEGER NOT NULL, recorded INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS affinity_plays (play_id TEXT NOT NULL,song_key TEXT NOT NULL,PRIMARY KEY(play_id,song_key))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS song_affinity (song_key TEXT PRIMARY KEY,artist TEXT NOT NULL,genre TEXT,listened INTEGER NOT NULL,plays INTEGER NOT NULL,last_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS song_affinity_rank ON song_affinity(listened DESC)")
    }

    private fun recentTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS recent_songs(song_id TEXT PRIMARY KEY,last_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS recent_song_times ON recent_songs(last_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        affinityTables(db)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE records ADD COLUMN title TEXT")
            db.execSQL("ALTER TABLE records ADD COLUMN album TEXT")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS completed_songs(song_key TEXT PRIMARY KEY,first_completed INTEGER NOT NULL)"
            )
            db.execSQL(
                "INSERT OR IGNORE INTO state VALUES ('history_generation',?)",
                arrayOf(java.util.UUID.randomUUID().toString()),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS records_ended ON records(kind,ended,song_id)")
            recentTables(db)
        }
    }

    fun record(record: ListeningRecord) =
        synchronized(lock) {
            require(
                record.startedAt >= 0 &&
                    record.endedAt >= record.startedAt &&
                    record.listenedMs >= 0
            )
            val json = gson.toJson(record)
            // Journal first: a failed DB update remains recoverable from the durable raw record.
            val root = File(context.filesDir, "listening_history").apply { mkdirs() }
            val month =
                Instant.ofEpochMilli(record.startedAt)
                    .atZone(ZoneId.of(record.timezone))
                    .toLocalDate()
                    .toString()
                    .take(7)
            val journal = File(root, "$month.jsonl")
            // Separate an interrupted last line before appending the next valid record.
            if (journal.length() > 0)
                java.io.RandomAccessFile(journal, "r").use { input ->
                    input.seek(journal.length() - 1)
                    if (input.read() != 10)
                        java.io.FileOutputStream(journal, true).use {
                            it.write(10)
                            it.fd.sync()
                        }
                }
            java.io.FileOutputStream(journal, true).use { out ->
                out.write((json + "\n").toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            writableDatabase.beginTransaction()
            try {
                upsert(record, json)
                state("journal_offset:${journal.name}", journal.length().toString())
                writableDatabase.setTransactionSuccessful()
            } finally {
                writableDatabase.endTransaction()
            }
            revision.update { it + 1 }
            PersonalArchive.enqueue(context)
            RecommendationTrainingWorker.enqueue(context)
        }

    private fun upsert(record: ListeningRecord, json: String) {
        val newer =
            readableDatabase
                .rawQuery("SELECT recorded,listened FROM records WHERE id=?", arrayOf(record.id))
                .use {
                    it.moveToFirst() &&
                        (it.getLong(0) > record.recordedAt ||
                            (it.getLong(0) == record.recordedAt &&
                                it.getLong(1) > record.listenedMs))
                }
        if (newer) return
        val values =
            ContentValues().apply {
                put("id", record.id)
                put("play_id", record.playId)
                put("kind", record.kind)
                put("song_key", record.songKey)
                put("song_id", record.songId)
                put("started", record.startedAt)
                put("ended", record.endedAt)
                put("listened", record.listenedMs)
                put("artist", record.artist)
                put("genre", record.genre)
                put("completed", if (record.completed) 1 else 0)
                put("recorded", record.recordedAt)
                put("payload", json)
                put("title", record.title)
                put("album", record.album)
            }
        check(
            writableDatabase.insertWithOnConflict(
                "records",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            ) != -1L
        )
        if (record.kind == "segment")
            indexAffinity(
                record.id,
                record.playId,
                record.songKey,
                record.artist,
                record.genre,
                record.listenedMs,
                record.endedAt,
                record.recordedAt,
            )
        if (record.kind in setOf("segment", "play") && (record.playCompletionRatio ?: 0.0) >= .9) {
            writableDatabase.execSQL(
                "INSERT OR IGNORE INTO completed_songs VALUES (?,?)",
                arrayOf<Any>(record.songKey, record.endedAt),
            )
            writableDatabase.execSQL(
                "UPDATE completed_songs SET first_completed=MIN(first_completed,?) WHERE song_key=?",
                arrayOf<Any>(record.endedAt, record.songKey),
            )
        }
        if (record.kind == "segment" || record.kind == "play") {
            writableDatabase.execSQL("INSERT OR IGNORE INTO state VALUES ('stats_revision','0')")
            writableDatabase.execSQL(
                "UPDATE state SET value=CAST(value AS INTEGER)+1 WHERE key='stats_revision'"
            )
        }
        if (record.kind == "segment") {
            writableDatabase.execSQL("INSERT OR IGNORE INTO state VALUES ('stats_max_end','0')")
            writableDatabase.execSQL(
                "UPDATE state SET value=MAX(CAST(value AS INTEGER),?) WHERE key='stats_max_end'",
                arrayOf(record.endedAt),
            )
            writableDatabase.execSQL(
                "INSERT OR IGNORE INTO recent_songs VALUES (?,?)",
                arrayOf<Any>(record.songId, record.endedAt),
            )
            writableDatabase.execSQL(
                "UPDATE recent_songs SET last_at=MAX(last_at,?) WHERE song_id=?",
                arrayOf<Any>(record.endedAt, record.songId),
            )
        }
    }

    /** Indexed, durable proof of an earlier complete play; old databases are upgraded lazily. */
    fun firstCompletedAt(key: String): Long? =
        synchronized(lock) {
            readableDatabase
                .rawQuery(
                    "SELECT first_completed FROM completed_songs WHERE song_key=?",
                    arrayOf(key),
                )
                .use { if (it.moveToFirst()) return@synchronized it.getLong(0) }
            val first =
                readableDatabase
                    .rawQuery(
                        "SELECT MIN(ended) FROM records WHERE song_key=? AND kind='play' AND completed=1",
                        arrayOf(key),
                    )
                    .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
            if (first != null)
                writableDatabase.execSQL(
                    "INSERT OR IGNORE INTO completed_songs VALUES (?,?)",
                    arrayOf<Any>(key, first),
                )
            first
        }

    fun hasUnindexedHistory(key: String): Boolean =
        state("compact_stats_ready") != "1" &&
            readableDatabase
                .rawQuery(
                    "SELECT 1 FROM records WHERE song_key=? AND kind IN ('segment','play') AND title IS NULL LIMIT 1",
                    arrayOf(key),
                )
                .use { it.moveToFirst() }

    fun statsVersion(): String =
        state("history_generation").orEmpty() + ":" + state("stats_revision").orEmpty()

    internal fun publishForHistory(generation: String, publish: () -> Unit): Boolean =
        synchronized(lock) {
            if (state("history_generation").orEmpty() != generation) return@synchronized false
            publish()
            true
        }

    fun lastSegmentEnded(): Long =
        state("stats_max_end")?.toLongOrNull()
            ?: readableDatabase
                .rawQuery("SELECT MAX(ended) FROM records WHERE kind='segment'", null)
                .use { c ->
                    val end = if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
                    state("stats_max_end", end.toString())
                    end
                }

    fun listenedDuration(start: Long, end: Long): Long {
        var total = 0L
        var until = start
        readableDatabase
            .rawQuery(
                "SELECT started,ended FROM records WHERE kind='segment' AND ended>? AND started<? AND listened>0 ORDER BY started,ended",
                arrayOf(start.toString(), end.toString()),
            )
            .use { c ->
                while (c.moveToNext()) {
                    val a = maxOf(start, c.getLong(0))
                    val b = minOf(end, c.getLong(1))
                    total += (b - maxOf(a, until)).coerceAtLeast(0)
                    until = maxOf(until, b)
                }
            }
        return total
    }

    private fun ensureRecentIndex() =
        synchronized(lock) {
            if (state("recent_index_ready") == "1") return@synchronized
            writableDatabase.beginTransaction()
            try {
                writableDatabase.execSQL(
                    "INSERT OR REPLACE INTO recent_songs SELECT song_id,MAX(ended) FROM records WHERE kind='segment' AND listened>0 GROUP BY song_id"
                )
                state("recent_index_ready", "1")
                writableDatabase.setTransactionSuccessful()
            } finally {
                writableDatabase.endTransaction()
            }
        }

    fun recentSongIds(limit: Int): List<String> {
        ensureRecentIndex()
        return buildList {
            readableDatabase
                .rawQuery(
                    "SELECT song_id FROM recent_songs ORDER BY last_at DESC,song_id LIMIT ?",
                    arrayOf(limit.coerceIn(1, 1000).toString()),
                )
                .use { c -> while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    fun songsHeardSince(cutoff: Long): Set<String> {
        ensureRecentIndex()
        return buildSet {
            readableDatabase
                .rawQuery(
                    "SELECT song_id FROM recent_songs WHERE last_at>=?",
                    arrayOf(cutoff.toString()),
                )
                .use { c -> while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    fun onRepeatSongIds(days: Int, limit: Int, now: Long): List<String> {
        val horizon = days.coerceIn(1, 365)
        val cutoff = now - horizon * 86_400_000L
        val scores = mutableMapOf<String, Double>()
        readableDatabase
            .rawQuery(
                "SELECT song_id,SUM(listened),MAX(ended) FROM records WHERE kind='segment' AND ended>=? AND listened>0 GROUP BY song_id,play_id",
                arrayOf(cutoff.toString()),
            )
            .use { c ->
                while (c.moveToNext()) {
                    val age =
                        ((now - c.getLong(2)).coerceAtLeast(0) / 86_400_000.0).coerceAtMost(
                            horizon.toDouble()
                        )
                    val score =
                        (1 + (c.getLong(1) / 60000.0).coerceAtMost(5.0)) *
                            (1 - age / (horizon * 1.5))
                    val id = c.getString(0)
                    scores[id] = (scores[id] ?: 0.0) + score
                }
            }
        return scores.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }
            )
            .take(limit.coerceIn(1, 1000))
            .map { it.key }
    }

    fun completedKeys(keys: Collection<String>): Set<String> = buildSet {
        for (batch in keys.distinct().chunked(400)) {
            if (batch.isEmpty()) continue
            val placeholders = List(batch.size) { "?" }.joinToString(",")
            readableDatabase
                .rawQuery(
                    "SELECT song_key FROM completed_songs WHERE song_key IN ($placeholders) UNION SELECT song_key FROM records WHERE kind='play' AND completed=1 AND song_key IN ($placeholders)",
                    (batch + batch).toTypedArray(),
                )
                .use { c -> while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    fun playSummary(playId: String): ListeningRecord? =
        readableDatabase
            .rawQuery(
                "SELECT payload FROM records WHERE id=? AND kind='play'",
                arrayOf("play:$playId"),
            )
            .use {
                if (it.moveToFirst())
                    runCatching { gson.fromJson(it.getString(0), ListeningRecord::class.java) }
                        .getOrNull()
                else null
            }

    fun ensureRecovered() {
        if (!recovered)
            synchronized(lock) {
                if (!recovered) {
                    recover()
                    recovered = true
                }
            }
    }

    fun statsPlayCounts(start: Long?, end: Long, songId: String? = null): Map<String, Int> =
        buildMap {
            val filter = if (songId == null) "" else " AND song_id=?"
            val args =
                mutableListOf((start ?: 0).toString(), end.toString()).apply {
                    if (songId != null) add(songId)
                }
            readableDatabase
                .rawQuery(
                    "SELECT song_id,COUNT(DISTINCT play_id) FROM records WHERE kind='segment' AND ended>? AND started<? AND listened>0$filter GROUP BY song_id",
                    args.toTypedArray(),
                )
                .use { c ->
                    while (c.moveToNext()) put(
                        c.getString(0),
                        c.getLong(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    )
                }
        }

    fun visitStatsRows(
        start: Long?,
        end: Long,
        songId: String? = null,
        visit: (ListeningStatsRow) -> Unit,
    ) {
        val args =
            mutableListOf((start ?: 0).toString(), end.toString()).apply {
                if (songId != null) add(songId)
            }
        val filter = if (songId == null) "" else " AND song_id=?"
        readableDatabase
            .rawQuery(
                "SELECT song_id,play_id,started,ended,artist,genre,title,album,CASE WHEN title IS NULL OR album IS NULL THEN payload ELSE NULL END FROM records WHERE kind='segment' AND ended>? AND started<? AND listened>0$filter ORDER BY started,ended",
                args.toTypedArray(),
            )
            .use { c ->
                while (c.moveToNext()) {
                    var title = if (c.isNull(6)) "" else c.getString(6)
                    var album = if (c.isNull(7)) "" else c.getString(7)
                    // Old rich payloads are skipped token by token, never deserialized into context
                    // trees.
                    if (!c.isNull(8))
                        runCatching {
                            com.google.gson.stream
                                .JsonReader(java.io.StringReader(c.getString(8)))
                                .use { reader ->
                                    reader.beginObject()
                                    while (reader.hasNext()) when (reader.nextName()) {
                                        "title" ->
                                            if (
                                                reader.peek() ==
                                                    com.google.gson.stream.JsonToken.STRING
                                            )
                                                title = reader.nextString()
                                            else reader.skipValue()
                                        "album" ->
                                            if (
                                                reader.peek() ==
                                                    com.google.gson.stream.JsonToken.STRING
                                            )
                                                album = reader.nextString()
                                            else reader.skipValue()
                                        else -> reader.skipValue()
                                    }
                                }
                        }
                    visit(
                        ListeningStatsRow(
                            c.getString(0),
                            c.getString(1),
                            c.getLong(2),
                            c.getLong(3),
                            title,
                            c.getString(4),
                            album,
                            if (c.isNull(5)) null else c.getString(5),
                        )
                    )
                }
            }
    }

    private fun indexAffinity(
        id: String,
        playId: String,
        key: String,
        artist: String,
        genre: String?,
        listened: Long,
        ended: Long,
        recorded: Long,
    ) {
        val previous =
            readableDatabase
                .rawQuery("SELECT listened,recorded FROM affinity_records WHERE id=?", arrayOf(id))
                .use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null }
        if (previous != null && previous.second > recorded) return
        val delta = listened - (previous?.first ?: 0L)
        val addedPlay =
            writableDatabase.insertWithOnConflict(
                "affinity_plays",
                null,
                ContentValues().apply {
                    put("play_id", playId)
                    put("song_key", key)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            ) != -1L
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO song_affinity(song_key,artist,genre,listened,plays,last_at) VALUES (?,?,?,0,0,0)",
            arrayOf(key, artist, genre),
        )
        writableDatabase.execSQL(
            "UPDATE song_affinity SET listened=MAX(0,listened+?),plays=plays+?,artist=CASE WHEN ?>=last_at THEN ? ELSE artist END,genre=CASE WHEN ?>=last_at THEN ? ELSE genre END,last_at=MAX(last_at,?) WHERE song_key=?",
            arrayOf<Any?>(delta, if (addedPlay) 1 else 0, ended, artist, ended, genre, ended, key),
        )
        writableDatabase.insertWithOnConflict(
            "affinity_records",
            null,
            ContentValues().apply {
                put("id", id)
                put("listened", listened)
                put("recorded", recorded)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /**
     * Resumable legacy migration: short transactions never hold the playback writer during cooling.
     */
    internal fun backfillAffinities(budget: AnalysisBudget) {
        if (state("affinity_index_ready") == "1") return
        var cursor = state("affinity_backfill_cursor")?.toLongOrNull() ?: 0L
        while (true) {
            budget.blockingCheckpoint()
            var rows = 0
            synchronized(lock) {
                writableDatabase.beginTransaction()
                try {
                    readableDatabase
                        .rawQuery(
                            "SELECT rowid,id,play_id,song_key,artist,genre,listened,ended,recorded FROM records WHERE kind='segment' AND rowid>? ORDER BY rowid LIMIT 64",
                            arrayOf(cursor.toString()),
                        )
                        .use { c ->
                            while (c.moveToNext()) {
                                indexAffinity(
                                    c.getString(1),
                                    c.getString(2),
                                    c.getString(3),
                                    c.getString(4),
                                    if (c.isNull(5)) null else c.getString(5),
                                    c.getLong(6),
                                    c.getLong(7),
                                    c.getLong(8),
                                )
                                cursor = c.getLong(0)
                                rows++
                            }
                        }
                    state("affinity_backfill_cursor", cursor.toString())
                    if (rows == 0) state("affinity_index_ready", "1")
                    writableDatabase.setTransactionSuccessful()
                } finally {
                    writableDatabase.endTransaction()
                }
            }
            if (rows == 0) break
        }
    }

    internal fun trainingRows(after: Long): List<Pair<Long, ListeningRecord>> = buildList {
        readableDatabase
            .rawQuery(
                "SELECT rowid,payload FROM records WHERE rowid>? AND kind IN ('segment','play') ORDER BY rowid LIMIT 32",
                arrayOf(after.toString()),
            )
            .use { c ->
                while (c.moveToNext()) runCatching {
                        gson.fromJson(c.getString(1), ListeningRecord::class.java)
                    }
                    .getOrNull()
                    ?.let { add(c.getLong(0) to it) }
            }
    }

    /** Charge-time, resumable migration of legacy display columns and actual coverage proofs. */
    internal fun backfillStats(budget: AnalysisBudget) {
        val generation = state("history_generation")
        var cursor = state("compact_stats_cursor")?.toLongOrNull() ?: 0L
        while (true) {
            budget.blockingCheckpoint()
            val rows = trainingRows(cursor)
            if (rows.isEmpty()) {
                state("compact_stats_ready", "1")
                return
            }
            for ((row, record) in rows) {
                budget.blockingCheckpoint()
                synchronized(lock) {
                    writableDatabase.beginTransaction()
                    try {
                        if (state("history_generation") != generation) throw AnalysisDeferred()
                        writableDatabase.execSQL(
                            "UPDATE records SET title=?,album=? WHERE id=? AND recorded=? AND (title IS NULL OR album IS NULL)",
                            arrayOf<Any>(record.title, record.album, record.id, record.recordedAt),
                        )
                        if ((record.playCompletionRatio ?: 0.0) >= .9) {
                            writableDatabase.execSQL(
                                "INSERT OR IGNORE INTO completed_songs VALUES (?,?)",
                                arrayOf<Any>(record.songKey, record.endedAt),
                            )
                            writableDatabase.execSQL(
                                "UPDATE completed_songs SET first_completed=MIN(first_completed,?) WHERE song_key=?",
                                arrayOf<Any>(record.endedAt, record.songKey),
                            )
                        }
                        state("compact_stats_cursor", row.toString())
                        writableDatabase.setTransactionSuccessful()
                    } finally {
                        writableDatabase.endTransaction()
                    }
                }
                cursor = row
            }
        }
    }

    internal fun previousGenre(record: ListeningRecord): String? =
        readableDatabase
            .rawQuery(
                "SELECT genre FROM records WHERE kind='play' AND started<? AND ended>=? ORDER BY started DESC LIMIT 1",
                arrayOf(record.startedAt.toString(), (record.startedAt - 30 * 60_000L).toString()),
            )
            .use { if (it.moveToFirst()) it.getString(0) else null }

    fun records(
        start: Long? = null,
        end: Long = Long.MAX_VALUE,
        kind: String = "segment",
        limit: Int = Int.MAX_VALUE,
    ): List<ListeningRecord> {
        val result = mutableListOf<ListeningRecord>()
        readableDatabase
            .rawQuery(
                "SELECT payload FROM records WHERE kind = ? AND ended >= ? AND started <= ? ORDER BY started DESC LIMIT ?",
                arrayOf(
                    kind,
                    (start ?: 0).toString(),
                    end.toString(),
                    limit.coerceAtLeast(1).toString(),
                ),
            )
            .use { cursor ->
                while (cursor.moveToNext()) result +=
                    gson.fromJson(cursor.getString(0), ListeningRecord::class.java)
            }
        return result.asReversed()
    }

    data class Affinity(
        val songKey: String,
        val artist: String,
        val genre: String?,
        val listenedMs: Long,
        val plays: Int,
        val lastAt: Long,
    )

    fun affinities(): List<Affinity> {
        val result = mutableListOf<Affinity>()
        readableDatabase
            .rawQuery(
                "SELECT song_key,artist,genre,listened,plays,last_at FROM song_affinity ORDER BY listened DESC LIMIT 20000",
                null,
            )
            .use { c ->
                while (c.moveToNext()) result +=
                    Affinity(
                        c.getString(0),
                        c.getString(1),
                        c.getString(2),
                        c.getLong(3),
                        c.getInt(4),
                        c.getLong(5),
                    )
            }
        return result
    }

    @Keep
    data class Monthly(val month: String, val listenedMs: Long, val plays: Int, val songs: Int)

    @Keep
    private data class MonthCache(val version: String, val zone: String, val months: List<Monthly>)

    @Volatile private var monthCache: MonthCache? = null

    fun months(): List<Monthly> {
        val version = statsVersion()
        val zone = ZoneId.systemDefault().id
        monthCache
            ?.takeIf { it.version == version && it.zone == zone }
            ?.let {
                return it.months
            }
        val file = File(context.filesDir, "listening_summaries/months.json")
        runCatching {
                require(file.length() in 1..128_000)
                gson.fromJson(file.readText(), MonthCache::class.java)
            }
            .getOrNull()
            ?.takeIf { it.version == version && it.zone == zone }
            ?.let {
                monthCache = it
                return it.months
            }
        val result = mutableListOf<Monthly>()
        readableDatabase
            .rawQuery(
                "SELECT strftime('%Y-%m', started / 1000, 'unixepoch', 'localtime'), SUM(listened), COUNT(DISTINCT play_id), COUNT(DISTINCT song_key) FROM records WHERE kind = 'segment' GROUP BY 1 ORDER BY 1 DESC",
                null,
            )
            .use { c ->
                while (c.moveToNext()) result +=
                    Monthly(c.getString(0), c.getLong(1), c.getInt(2), c.getInt(3))
            }
        if (statsVersion() == version)
            publishForHistory(version.substringBefore(':')) {
                val cached = MonthCache(version, zone, result)
                PersonalArchive.atomicWrite(file, gson.toJson(cached).toByteArray())
                monthCache = cached
            }
        return result
    }

    fun feature(key: String, signature: String? = null): AudioFeatures? =
        readableDatabase
            .rawQuery("SELECT signature, payload FROM features WHERE song_key = ?", arrayOf(key))
            .use { c ->
                if (!c.moveToFirst() || signature != null && c.getString(0) != signature) null
                else gson.fromJson(c.getString(1), AudioFeatures::class.java)
            }

    fun saveFeature(key: String, signature: String, feature: AudioFeatures) =
        synchronized(lock) {
            val payload = gson.toJson(feature.copy(sourceSignature = signature))
            writableDatabase.insertWithOnConflict(
                "features",
                null,
                ContentValues().apply {
                    put("song_key", key)
                    put("signature", signature)
                    put("payload", payload)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            val directory =
                File(context.filesDir, "music_metadata/audio_features").apply { mkdirs() }
            PersonalArchive.atomicWrite(
                File(directory, "${kr.co.metadata.crawler.CatalogFileStore.hash(key)}.json"),
                payload.toByteArray(),
            )
            revision.update { it + 1 }
            PersonalArchive.enqueue(context)
        }

    /** Restore is idempotent; older checkpoints cannot replace a newer complete record. */
    fun importJournal(file: File): Int =
        synchronized(lock) {
            var count = 0
            writableDatabase.beginTransaction()
            try {
                file.useLines { lines ->
                    lines.forEach { line ->
                        if (line.isBlank()) return@forEach
                        val r =
                            runCatching { gson.fromJson(line, ListeningRecord::class.java) }
                                .getOrNull() ?: return@forEach
                        if (
                            r.id.isBlank() ||
                                r.songKey.isBlank() ||
                                r.endedAt < r.startedAt ||
                                r.listenedMs < 0
                        )
                            return@forEach
                        val current =
                            readableDatabase
                                .rawQuery(
                                    "SELECT recorded FROM records WHERE id = ?",
                                    arrayOf(r.id),
                                )
                                .use { if (it.moveToFirst()) it.getLong(0) else -1 }
                        if (r.recordedAt >= current) {
                            upsert(r, line)
                            count++
                        }
                    }
                }
                writableDatabase.setTransactionSuccessful()
            } finally {
                writableDatabase.endTransaction()
            }
            revision.update { it + 1 }
            count
        }

    fun recover() =
        synchronized(lock) {
            val root = File(context.filesDir, "listening_history")
            root
                .listFiles { file -> file.extension == "jsonl" }
                ?.forEach { file ->
                    val offset =
                        state("journal_offset:${file.name}")
                            ?.toLongOrNull()
                            ?.coerceAtMost(file.length()) ?: 0L
                    if (offset == file.length()) return@forEach
                    val tail = File.createTempFile("journal-tail-", ".jsonl", context.cacheDir)
                    try {
                        file.inputStream().use { input ->
                            var remaining = offset
                            while (remaining > 0) {
                                val skipped = input.skip(remaining)
                                if (skipped <= 0) break
                                remaining -= skipped
                            }
                            tail.outputStream().use { input.copyTo(it) }
                        }
                        importJournal(tail)
                        state("journal_offset:${file.name}", file.length().toString())
                    } finally {
                        tail.delete()
                    }
                }
        }

    fun clearRecords() =
        synchronized(lock) {
            state("history_generation", java.util.UUID.randomUUID().toString())
            ContextPreferenceIndex(context).use { it.reset() }
            File(context.cacheDir, "listening-insights").deleteRecursively()
            File(context.filesDir, "listening_insights").deleteRecursively()
            File(context.filesDir, "listening_summaries").deleteRecursively()
            monthCache = null
            writableDatabase.delete("records", null, null)
            writableDatabase.delete("completed_songs", null, null)
            writableDatabase.delete("recent_songs", null, null)
            state("recent_index_ready", "1")
            state("stats_max_end", "0")
            state("compact_stats_cursor", "0")
            state("compact_stats_ready", "1")
            writableDatabase.delete("affinity_records", null, null)
            writableDatabase.delete("affinity_plays", null, null)
            writableDatabase.delete("song_affinity", null, null)
            File(context.filesDir, "listening_history").listFiles()?.forEach { it.delete() }
            revision.update { it + 1 }
        }

    fun copyJournal(source: File, destination: File) {
        // Journals append under lock. Pin a complete prefix, then release the writer immediately.
        val (input, length) = synchronized(lock) { source.inputStream() to source.length() }
        input.use { stream ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val count =
                        stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    check(count > 0) { "Journal prefix was truncated" }
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
        }
    }

    fun invalidate() {
        revision.update { it + 1 }
    }

    fun state(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM state WHERE key = ?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun state(key: String, value: String) {
        writableDatabase.insertWithOnConflict(
            "state",
            null,
            ContentValues().apply {
                put("key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    companion object {
        @Volatile private var instance: ListeningLedger? = null

        fun get(context: Context): ListeningLedger =
            instance
                ?: synchronized(this) {
                    instance ?: ListeningLedger(context.applicationContext).also { instance = it }
                }
    }
}
