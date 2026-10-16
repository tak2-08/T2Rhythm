// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.shared.data.repository.PlaybackStatsRepository
import chromahub.rhythm.app.shared.data.repository.StatsTimeRange
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LegacyHistoryMigrationTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun interruptedImportResumesWithoutDuplicatePlaysAndCompatibilityWritesRemainVisible() =
        runBlocking {
            val root = File(base.cacheDir, "legacy-import-${UUID.randomUUID()}").apply { mkdirs() }
            val context =
                object : ContextWrapper(base) {
                    override fun getApplicationContext(): Context = this

                    override fun getFilesDir() = File(root, "files").apply { mkdirs() }

                    override fun getDatabasePath(name: String) =
                        File(name).takeIf { it.isAbsolute } ?: File(root, name)
                }
            val db =
                ListeningLedger::class
                    .java
                    .getDeclaredConstructor(Context::class.java)
                    .apply { isAccessible = true }
                    .newInstance(context)
            val singleton =
                ListeningLedger::class.java.getDeclaredField("instance").apply {
                    isAccessible = true
                }
            val previous = singleton.get(null)
            singleton.set(null, db)
            val repository =
                PlaybackStatsRepository::class
                    .java
                    .getDeclaredConstructor(Context::class.java)
                    .apply { isAccessible = true }
                    .newInstance(context)
            val source = File(context.filesDir, "playback_history.json")
            val now = System.currentTimeMillis() - 3600_000
            fun event(i: Int) =
                """{"songId":"song","timestamp":${now + (i + 1) * 1000},"durationMs":1000,"songTitle":"오래된 노래","artistName":"가수"}"""
            fun lines() =
                File(context.filesDir, "listening_history").listFiles().orEmpty().sumOf {
                    it.useLines { lines -> lines.count() }
                }
            try {
                source.writeText((0..127).joinToString(",", "[", ",{") { event(it) })
                assertEquals(128, repository.loadSummary(StatsTimeRange.ALL_TIME).totalPlayCount)
                assertNull(db.state("legacy_history_imported"))
                assertEquals(128, lines())
                // A repeated UI read backs off instead of hammering the same damaged source.
                repository.loadSummary(StatsTimeRange.ALL_TIME)
                assertEquals(128, lines())
                val repaired = (0..128).joinToString(",", "[", "]") { event(it) }
                source.writeText(repaired)
                assertEquals(129, repository.loadSummary(StatsTimeRange.ALL_TIME).totalPlayCount)
                assertEquals("1", db.state("legacy_history_imported"))
                assertEquals(129, lines())
                assertEquals(repaired, source.readText())
                repository.recordSimplePlayback(
                    "new",
                    "새 노래",
                    "다른 가수",
                    null,
                    null,
                    2000,
                    now + 200000,
                )
                assertEquals(130, repository.loadSummary(StatsTimeRange.ALL_TIME).totalPlayCount)
                assertEquals(
                    131000L,
                    repository.loadSummary(StatsTimeRange.ALL_TIME).totalDurationMs,
                )
                assertEquals(repaired, source.readText())
            } finally {
                singleton.set(null, previous)
                db.close()
                root.deleteRecursively()
            }
        }

    @Test
    fun importedJournalsRecoverWhenTheDerivedDatabaseIsRebuilt() {
        val root = File(base.cacheDir, "legacy-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        val context =
            object : ContextWrapper(base) {
                override fun getFilesDir() = File(root, "files").apply { mkdirs() }

                override fun getDatabasePath(name: String) =
                    File(name).takeIf { it.isAbsolute } ?: File(root, name)
            }
        fun ledger() =
            ListeningLedger::class
                .java
                .getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }
                .newInstance(context)
        val records =
            (0 until 128).map { i ->
                ListeningRecord(
                    "legacy:$i",
                    "legacy:$i",
                    "segment",
                    "song",
                    "legacy:song",
                    "노래",
                    "가수",
                    "앨범",
                    null,
                    i * 1000L,
                    (i + 1) * 1000L,
                    1000,
                    0,
                    0,
                    0,
                    1f,
                    "legacy_import",
                )
            }
        try {
            ledger().use { db ->
                db.importLegacyBatch(records)
                db.importLegacyBatch(records)
                assertEquals(128, db.statsPlayCounts(null, 200000)["song"])
            }
            context.getDatabasePath("listening_ledger.db").delete()
            ledger().use { db ->
                db.ensureRecovered()
                assertEquals(128, db.statsPlayCounts(null, 200000)["song"])
                assertEquals(128000L, db.listenedDuration(0, 200000))
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
