// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.features.local.data.metadata.ArchiveSyncIndex
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.shared.data.repository.*
import com.google.gson.Gson
import java.io.File
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PerformancePersistenceTest {
    @get:org.junit.Rule val analysisLoad = chromahub.rhythm.app.features.local.data.experience.AnalysisRetryRule()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private fun isolated(root: File) =
        object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }

            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }

            override fun getDatabasePath(name: String) =
                File(name).takeIf { it.isAbsolute } ?: File(root, name)
        }

    private fun ledger(context: Context) =
        ListeningLedger::class
            .java
            .getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(context)

    private fun root() =
        File(context.cacheDir, "performance-${UUID.randomUUID()}").apply { mkdirs() }

    @Test
    fun v2HistoryUpgradesWithoutLosingRichRecordsAndBackfillsCompactColumns() {
        val root = root()
        val ctx = isolated(root)
        val old =
            ListeningRecord(
                "old",
                "old-play",
                "segment",
                "old-song",
                "content://test/old",
                "기존 제목",
                "가수",
                "앨범",
                "Pop",
                1000,
                97000,
                96000,
                100000,
                0,
                96000,
                1f,
                "pause",
                situation = ListeningSituation(routeName = "기존 이어폰"),
                playCompletionRatio = .96,
            )
        try {
            SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath("listening_ledger.db"), null)
                .use { db ->
                    db.execSQL(
                        "CREATE TABLE records (id TEXT PRIMARY KEY, play_id TEXT NOT NULL, kind TEXT NOT NULL, song_key TEXT NOT NULL, song_id TEXT NOT NULL, started INTEGER NOT NULL, ended INTEGER NOT NULL, listened INTEGER NOT NULL, artist TEXT NOT NULL, genre TEXT, completed INTEGER NOT NULL, recorded INTEGER NOT NULL, payload TEXT NOT NULL)"
                    )
                    db.execSQL("CREATE TABLE state(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
                    db.execSQL(
                        "CREATE TABLE features(song_key TEXT PRIMARY KEY,signature TEXT NOT NULL,payload TEXT NOT NULL)"
                    )
                    db.insertOrThrow(
                        "records",
                        null,
                        ContentValues().apply {
                            put("id", old.id)
                            put("play_id", old.playId)
                            put("kind", old.kind)
                            put("song_key", old.songKey)
                            put("song_id", old.songId)
                            put("started", old.startedAt)
                            put("ended", old.endedAt)
                            put("listened", old.listenedMs)
                            put("artist", old.artist)
                            put("genre", old.genre)
                            put("completed", 0)
                            put("recorded", old.recordedAt)
                            put("payload", Gson().toJson(old))
                        },
                    )
                    db.version = 2
                }
            ledger(ctx).use { db ->
                val rows = mutableListOf<ListeningStatsRow>()
                db.visitStatsRows(null, Long.MAX_VALUE) { rows += it }
                assertEquals("기존 제목", rows.single().title)
                assertTrue(db.hasUnindexedHistory(old.songKey))
                assertEquals(listOf(old.songId), db.recentSongIds(10))
                assertEquals("기존 이어폰", db.records().single().situation!!.routeName)
                db.backfillStats(AnalysisBudget(context, maxMillis = 60000))
                assertEquals(old.endedAt, db.firstCompletedAt(old.songKey))
                assertFalse(db.hasUnindexedHistory(old.songKey))
                db.readableDatabase
                    .rawQuery("SELECT title,album FROM records WHERE id='old'", null)
                    .use {
                        assertTrue(it.moveToFirst())
                        assertEquals(old.title, it.getString(0))
                        assertEquals(old.album, it.getString(1))
                    }
            }
            ledger(ctx).use { db -> assertEquals(old.endedAt, db.firstCompletedAt(old.songKey)) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun completeProofSurvivesRestartWhileLaterRawCompletionRemainsTruthful() {
        val root = root()
        val ctx = isolated(root)
        try {
            val full =
                ListeningRecord(
                    "play:full",
                    "full",
                    "play",
                    "s",
                    "content://test/full",
                    "제목",
                    "가수",
                    "앨범",
                    null,
                    1000,
                    101000,
                    100000,
                    100000,
                    0,
                    100000,
                    1f,
                    "ended",
                    completed = true,
                    playCompletionRatio = 1.0,
                )
            ledger(ctx).use { it.record(full) }
            ledger(ctx).use { db ->
                val brief =
                    full.copy(
                        id = "play:brief",
                        playId = "brief",
                        startedAt = 200000,
                        endedAt = 201000,
                        listenedMs = 1000,
                        endPositionMs = 1000,
                        completed = false,
                        playCompletionRatio = .01,
                    )
                db.record(brief)
                assertNull(
                    ListeningPolicy.outcome(
                        .01,
                        db.firstCompletedAt(full.songKey),
                        brief.startedAt,
                        false,
                    )
                )
                assertEquals(.01, db.playSummary("brief")!!.playCompletionRatio!!, .0)
                val generation = db.statsVersion()
                db.clearRecords()
                assertNotEquals(generation, db.statsVersion())
                assertNull(db.firstCompletedAt(full.songKey))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun archiveReceiptMigrationPreservesSettingsAndCommitsBatchesAcrossRestart() {
        val root = root()
        val ctx = isolated(root)
        val name = "archive-test-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            prefs
                .edit()
                .putString("tree", "content://test/tree")
                .putLong("lastSync", 123)
                .apply { repeat(1000) { putString("synced:private-art-v1:$it", "$it:123") } }
                .commit()
            ArchiveSyncIndex(ctx).use { index ->
                index.migrate(prefs)
                assertEquals("content://test/tree", prefs.getString("tree", null))
                assertEquals(123L, prefs.getLong("lastSync", 0))
                assertFalse(prefs.all.keys.any { it.startsWith("synced:") })
                assertEquals("999:123", index.signature("synced:private-art-v1:999"))
                index.remember("new", "new-signature")
            }
            ArchiveSyncIndex(ctx).use { index ->
                index.migrate(prefs)
                assertEquals("new-signature", index.signature("new"))
                assertEquals("999:123", index.signature("synced:private-art-v1:999"))
            }
        } finally {
            context.deleteSharedPreferences(name)
            root.deleteRecursively()
        }
    }

    @Test
    fun summaryCacheSurvivesARepositoryRecreationAndRejectsClearedHistory() {
        val root = root()
        val ctx = isolated(root)
        try {
            val stats =
                StreamingPlaybackStats(
                    StatsTimeRange.ALL_TIME,
                    100000,
                    java.time.ZoneId.of("UTC"),
                    Locale.KOREAN,
                )
            stats.add(ListeningStatsRow("s", "p", 1000, 61000, "제목", "가수", "앨범", "Pop"))
            val summary = stats.finish(mapOf("s" to 1))
            PlaybackSummaryCache(ctx)
                .save(
                    CachedPlaybackSummary(
                        key = "today",
                        version = "generation:1",
                        library = "library",
                        builtAt = System.currentTimeMillis(),
                        summary = summary,
                    )
                )
            assertEquals(summary, PlaybackSummaryCache(ctx).read("today", "generation")!!.summary)
            assertNull(PlaybackSummaryCache(ctx).read("today", "new-generation"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun tenThousandAutomaticVolumeCallbacksDoNotCreateATelemetryFlood() = runBlocking {
        val nonce = "gain-${UUID.randomUUID()}"
        val item = MediaItem.fromUri(Uri.parse("content://test/$nonce"))
        val position = AtomicLong()
        val fake =
            Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) {
                _,
                method,
                _ ->
                when (method.name) {
                    "getCurrentMediaItem" -> item
                    "getCurrentPosition" -> position.get()
                    "getPlaybackParameters" -> PlaybackParameters.DEFAULT
                    "getVolume" -> .5f
                    "isPlaying",
                    "getPlayWhenReady" -> true
                    else ->
                        when (method.returnType) {
                            Boolean::class.javaPrimitiveType -> false
                            Int::class.javaPrimitiveType -> 0
                            Long::class.javaPrimitiveType -> 0L
                            Float::class.javaPrimitiveType -> 0f
                            else -> null
                        }
                }
            } as Player
        val song =
            Song(
                nonce,
                "Gain test",
                "Artist",
                "Album",
                duration = 100000,
                uri = Uri.parse("content://test/$nonce"),
            )
        var recorder: ListeningRecorder? = null
        instrumentation.runOnMainSync {
            recorder = ListeningRecorder(context) { song }
            recorder!!.attach(fake)
        }
        delay(100)
        position.set(100)
        instrumentation.runOnMainSync {
            repeat(10000) { recorder!!.onVolumeChanged((it % 100) / 100f) }
            recorder!!.close()
        }
        val db = ListeningLedger.get(context)
        withTimeout(30000) {
            while (db.records(kind = "play", limit = 32).none { it.songId == nonce }) delay(100)
        }
        assertEquals(1, db.records(kind = "action", limit = 64).count { it.songId == nonce })
        assertEquals(1, db.records(kind = "segment", limit = 64).count { it.songId == nonce })
    }
}
