// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.maintenance

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.features.local.data.experience.*
import chromahub.rhythm.app.features.local.data.metadata.*
import chromahub.rhythm.app.features.local.data.video.*
import chromahub.rhythm.app.shared.data.model.Song
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kr.co.metadata.crawler.CatalogField
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

class MaintenanceLifecycleTest {
    @get:org.junit.Rule val analysisLoad = chromahub.rhythm.app.features.local.data.experience.AnalysisRetryRule()
    companion object {
        @BeforeClass
        @JvmStatic
        fun pauseUnrelatedDebugJobsDuringIsolatedFixtures() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val manager = androidx.work.WorkManager.getInstance(ctx)
            for (name in
                listOf(
                    "audio_feature_analysis",
                    "recommendation_training_v4",
                    "recommendation_training_periodic_v6",
                    "enrichment_learning",
                    "enrichment_learning_periodic",
                    "history_retention_initial",
                    "history_retention",
                    "personal_archive_sync_v2",
                    "personal_archive_periodic_v2",
                    "library_catalog_enrichment",
                )) manager.cancelUniqueWork(name).result.get()
        }
    }

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun isolated(root: File) =
        object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }

            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }

            override fun getDatabasePath(name: String) =
                File(name).takeIf { it.isAbsolute } ?: File(root, name).apply { parentFile!!.mkdirs() }
        }

    private fun ledger(ctx: Context) =
        ListeningLedger::class
            .java
            .getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(ctx)

    private fun root() =
        File(context.cacheDir, "maintenance-${UUID.randomUUID()}").apply { mkdirs() }

    @Test
    fun completedInputsAndSettingsExcludeWorkButNewOrChangedTracksRemainEligible() {
        val root = root()
        val ctx = isolated(root)
        val history = CatalogBatchHistory(ctx)
        val song =
            Song("old", "곡", "가수", "앨범", duration = 200000, uri = Uri.parse("content://test/1"))
        val updated = song.copy(album = "수집한 앨범", genre = "Pop", year = 2026)
        val other = song.copy(id = "new", uri = Uri.parse("content://test/2"))
        val options = CatalogOptions()
        try {
            assertEquals(2, history.pending(listOf(song, other), options).size)
            history.complete(song, updated, options)
            assertEquals(listOf(other), history.pending(listOf(song, other), options))
            assertTrue(history.pending(listOf(updated), options).isEmpty())
            assertEquals(1, history.pending(listOf(updated.copy(title = "직접 수정")), options).size)
            assertEquals(
                1,
                history
                    .pending(
                        listOf(updated),
                        options.copy(forceFields = setOf(CatalogField.TRACK_ALBUM_TITLE)),
                    )
                    .size,
            )
            assertTrue(
                history
                    .pending(
                        listOf(updated),
                        options.copy(providers = options.providers.reversed().toSet()),
                    )
                    .isEmpty()
            )
        } finally {
            history.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyCompletionIsImportedOnceAndAmbiguousFailuresStayEligible() {
        val root = root()
        val ctx = isolated(root)
        val catalog = CatalogEnrichmentService.get(context)
        val files =
            listOf(File(catalog.root, "batch.json"), File(catalog.root, "batch-progress.json"))
        val backups = files.associateWith { it.takeIf { file -> file.exists() }?.readBytes() }
        val song =
            Song(
                "legacy-checked",
                "곡",
                "가수",
                "앨범",
                duration = 200000,
                uri = Uri.parse("content://test/legacy"),
            )
        val options = CatalogOptions()
        try {
            val plan = CatalogBatchPlan.create(context, listOf(song))
            plan.copy(next = 1).save(context)
            CatalogBatchHistory(ctx).use { history ->
                history.migrateLegacy(listOf(song), options)
                assertTrue(history.pending(listOf(song), options).isEmpty())
                val changed = options.copy(forceFields = setOf(CatalogField.TRACK_GENRES))
                history.migrateLegacy(listOf(song), changed)
                assertEquals(1, history.pending(listOf(song), changed).size)
            }
            plan.copy(next = 1, failed = 1).save(context)
            CatalogBatchHistory(isolated(File(root, "ambiguous"))).use { history ->
                history.migrateLegacy(listOf(song), options)
                assertEquals(1, history.pending(listOf(song), options).size)
            }
        } finally {
            backups.forEach { (file, data) ->
                if (data == null) file.delete() else file.writeBytes(data)
            }
            root.deleteRecursively()
        }
    }

    @Test
    fun deletingFailuresRemovesAllPartialsAndHistoryAndRejectsLateWorkerWrites() = runBlocking {
        val root = root()
        val ctx = isolated(root)
        val ids = List(4) { UUID.randomUUID().toString() }
        val media = mutableListOf<Uri>()
        try {
            val failed = DownloadRecord(ids[0], "AUDIO", "k", "source", "실패", state = "FAILED")
            val cancelled = failed.copy(id = ids[1], state = "CANCELLED")
            val done = failed.copy(id = ids[2], state = "SUCCEEDED")
            val active = failed.copy(id = ids[3], state = "RUNNING")
            listOf(failed, cancelled, done, active).forEach { DownloadRegistry.save(ctx, it) }
            val part =
                File(ctx.cacheDir, "media_transfers/${failed.id}.part").apply {
                    parentFile!!.mkdirs()
                    writeBytes(ByteArray(4096))
                }
            val resume = File(part.parentFile, "${failed.id}.json").apply { writeText("resume") }
            val hls =
                File(ctx.cacheDir, "hls_transfers/${failed.id}/chunk.ts").apply {
                    parentFile!!.mkdirs()
                    writeText("partial")
                }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                for (pending in listOf(1, 0)) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, "maintenance-$pending-${failed.id}.wav")
                        put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Music/T2Rhythm/")
                        put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                        put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
                    }
                    val uri = checkNotNull(ctx.contentResolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
                    media += uri
                    val wave = java.nio.ByteBuffer.allocate(16044).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    wave.put("RIFF".toByteArray()).putInt(16036).put("WAVEfmt ".toByteArray()).putInt(16)
                        .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
                        .put("data".toByteArray()).putInt(16000)
                    ctx.contentResolver.openOutputStream(uri)!!.use { it.write(wave.array()) }
                    if (pending == 0) ctx.contentResolver.update(uri, android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0)
                    }, null, null)
                }
            }
            DownloadRegistry.removeAllFailed(ctx)
            assertNull(DownloadRegistry.get(ctx, failed.id))
            assertNull(DownloadRegistry.get(ctx, cancelled.id))
            assertFalse(part.exists())
            assertFalse(resume.exists())
            assertFalse(hls.exists())
            assertNotNull(DownloadRegistry.get(ctx, done.id))
            assertNotNull(DownloadRegistry.get(ctx, active.id))
            if (media.isNotEmpty()) {
                fun exists(uri: Uri) = ctx.contentResolver.query(uri, arrayOf("_id"), null, null, null)?.use { it.moveToFirst() } == true
                assertFalse("An orphaned pending file must be removed even without a registry URI", exists(media[0]))
                assertTrue("A completed file must survive failed-history cleanup", exists(media[1]))
            }
            DownloadRegistry.save(ctx, failed.copy(state = "RUNNING"))
            assertNull(DownloadRegistry.get(ctx, failed.id))
            assertFalse(File(ctx.filesDir, "music_metadata/downloads/${failed.id}.json").exists())
        } finally {
            media.forEach { uri ->
                val present = ctx.contentResolver.query(uri, arrayOf("_id"), null, null, null)?.use { it.moveToFirst() } == true
                if (present) ctx.contentResolver.delete(uri, null, null)
            }
            ctx.getSharedPreferences("removed_downloads", Context.MODE_PRIVATE)
                .edit()
                .apply { ids.forEach(::remove) }
                .commit()
            root.deleteRecursively()
        }
    }

    @Test
    fun periodicMaintenanceDoesNotStartDuringApplicationStartup() {
        val manager = androidx.work.WorkManager.getInstance(context)
        HistoryRetention.enqueue(context)
        try {
            val periodic = manager.getWorkInfosForUniqueWork("history_retention").get()
                .first { !it.state.isFinished }
            assertTrue(periodic.initialDelayMillis >= java.util.concurrent.TimeUnit.MINUTES.toMillis(20))
            assertFalse(periodic.constraints.requiresDeviceIdle())
            assertFalse(periodic.constraints.requiresCharging())
        } finally {
            manager.cancelUniqueWork("history_retention").result.get()
            manager.cancelUniqueWork("history_retention_initial").result.get()
        }
    }

    @Test
    fun legacyChargingAndIdleWaitsAreRelaxedWithoutReplacingTheirWorkId() {
        val manager = androidx.work.WorkManager.getInstance(context)
        val name = "maintenance-migration:${UUID.randomUUID()}"
        val request =
            androidx.work
                .OneTimeWorkRequestBuilder<AudioAnalysisWorker>()
                .setInitialDelay(2, java.util.concurrent.TimeUnit.DAYS)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresDeviceIdle(true)
                        .build()
                )
                .build()
        manager.enqueueUniqueWork(name, androidx.work.ExistingWorkPolicy.KEEP, request).result.get()
        try {
            AnalysisScheduling.relax(context, name, AudioAnalysisWorker::class.java)
            val updated = manager.getWorkInfosForUniqueWork(name).get().single()
            assertEquals(request.id, updated.id)
            assertFalse(updated.constraints.requiresCharging())
            assertFalse(updated.constraints.requiresDeviceIdle())
            assertTrue(updated.constraints.requiresBatteryNotLow())
        } finally {
            manager.cancelUniqueWork(name).result.get()
        }
    }

    @Test
    fun summariesPreserveStatisticsAffinitiesAndCompletionAcrossOldImportsAndFullRebuild() {
        val root = root()
        val ctx = isolated(root)
        val gson = Gson()
        var ledger = ledger(ctx)
        val base = java.time.Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()
        val now = System.currentTimeMillis()
        val detail =
            ListeningSituation(
                latitude = 37.12345,
                longitude = 127.12345,
                precise = true,
                routeType = "bluetooth",
                routeName = "이어폰",
                hour = 12,
                weekday = 3,
            )
        fun row(id: String, start: Long, end: Long) =
            ListeningRecord(
                id,
                "play",
                "segment",
                "song",
                "key",
                "곡",
                "가수",
                "앨범",
                "Pop",
                start,
                end,
                end - start,
                120000,
                0,
                end - start,
                1f,
                "pause",
                recordedAt = end,
                timezone = "UTC",
                situation = detail,
                playCompletionRatio = .96,
            )
        val a = row("a", base, base + 30000)
        val b = row("b", base + 30000, base + 60000).copy(playCompletionRatio = .5)
        val c = row("c", base + 90000, base + 120000).copy(playCompletionRatio = .5)
        val play =
            c.copy(
                id = "play-end",
                kind = "play",
                startedAt = base,
                endedAt = base + 120000,
                listenedMs = 90000,
            )
        val fresh =
            row("recent", now - 30000, now)
                .copy(playId = "recent-play", songKey = "recent-key", songId = "recent-song")
        val journal =
            File(ctx.filesDir, "listening_history/2025-01.jsonl").apply { parentFile!!.mkdirs() }
        val old = File(ctx.cacheDir, "original.jsonl")
        val recent =
            File(journal.parentFile, java.time.LocalDate.now().toString().take(7) + ".jsonl")
        try {
            journal.writeText(
                listOf(a.copy(listenedMs = 10000, recordedAt = base), a, b, c, play).joinToString(
                    "\n",
                    postfix = "\n",
                ) {
                    gson.toJson(it)
                }
            )
            old.writeBytes(journal.readBytes())
            recent.writeText(gson.toJson(fresh) + "\n")
            val recentBytes = recent.readBytes()
            ledger.recover()
            val obsolete = File(ctx.cacheDir, "obsolete-summary.jsonl")
            obsolete.writeText(
                gson.toJson(
                    ListeningCompaction.summarizePlay(listOf(a, b)).single().copy(recordedAt = 0)
                ) + "\n"
            )
            ledger.importJournal(obsolete)
            assertEquals(4, ledger.records().size)
            val beforeCounts = ledger.statsPlayCounts(null, Long.MAX_VALUE)
            val beforeAffinities = ledger.affinities().associateBy { it.songKey }
            val beforeCompletion = ledger.firstCompletedAt("key")
            val beforeTime = ledger.records().sumOf { it.listenedMs }
            val cutoff = now - HistoryRetention.DETAIL_DAYS * 86400000L
            val budget = AnalysisBudget(ctx)
            assertEquals(4, ledger.compactBefore(cutoff, budget))
            ledger.rewriteCompactedJournals(cutoff, budget)
            assertTrue(journal.length() < old.length())
            assertArrayEquals(recentBytes, recent.readBytes())
            assertEquals(3, ledger.records().size)
            assertEquals(beforeTime, ledger.records().sumOf { it.listenedMs })
            assertEquals(beforeCounts, ledger.statsPlayCounts(null, Long.MAX_VALUE))
            assertEquals(beforeAffinities, ledger.affinities().associateBy { it.songKey })
            assertEquals(beforeCompletion, ledger.firstCompletedAt("key"))
            ledger.importJournal(old)
            ledger.importJournal(journal)
            ledger.importJournal(old)
            assertEquals(beforeCounts, ledger.statsPlayCounts(null, Long.MAX_VALUE))
            assertEquals(beforeTime, ledger.records().sumOf { it.listenedMs })
            assertEquals(beforeAffinities, ledger.affinities().associateBy { it.songKey })
            ledger.close()
            android.database.sqlite.SQLiteDatabase.deleteDatabase(
                ctx.getDatabasePath("listening_ledger.db")
            )
            ledger = ledger(ctx)
            ledger.recover()
            assertEquals(beforeCounts, ledger.statsPlayCounts(null, Long.MAX_VALUE))
            assertEquals(beforeTime, ledger.records().sumOf { it.listenedMs })
            assertEquals(beforeCompletion, ledger.firstCompletedAt("key"))
            assertEquals(beforeAffinities, ledger.affinities().associateBy { it.songKey })
            assertEquals(0, ledger.compactBefore(cutoff, AnalysisBudget(ctx)))
        } finally {
            ledger.close()
            root.deleteRecursively()
        }
    }
}
