// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.metadata

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.features.local.data.database.RhythmDatabase
import chromahub.rhythm.app.features.local.data.database.entity.toEntity
import chromahub.rhythm.app.features.local.data.discovery.*
import chromahub.rhythm.app.features.local.data.experience.*
import chromahub.rhythm.app.features.local.data.repository.MusicRepository
import chromahub.rhythm.app.features.local.data.video.*
import chromahub.rhythm.app.shared.data.model.LyricsData
import chromahub.rhythm.app.shared.data.model.Song
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AssetIntegrityRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun song(root: File, id: String = "900001") = Song(id, "테스트 음악", "가수", "앨범", duration = 100_000,
        uri = Uri.parse("content://media/external/audio/media/$id"), path = File(root, "song.wav").path)
    private fun isolated(root: File) = object : ContextWrapper(context) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    }
    @Test fun databaseRebuildAndEmbeddedExtractionPreserveManualCoverAndVideo() = runBlocking {
        val root = File(context.cacheDir, "assets-${UUID.randomUUID()}").apply { mkdirs() }
        val original = song(root)
        val art = File(root, "choice.webp").apply { writeText("owned cover") }
        val choices = SongArtworkOverrides(context)
        val videoRoot = File(context.filesDir, "music_metadata/videos").apply { mkdirs() }
        val videoFile = File(videoRoot, "${UUID.randomUUID()}.mp4").apply { writeText("video asset") }
        val repository = MusicRepository(context)
        val video = MusicVideoRepository(context)
        try {
            choices.save(original, Uri.fromFile(art))
            video.save(MusicVideo(original.uri.toString(), "local", fileName = videoFile.name, bytes = videoFile.length()))
            RhythmDatabase.getInstance(context).songDao().upsertAll(listOf(original.toEntity()))
            repository.clearSongCacheData()
            val fresh = original.copy(id = "900002", uri = Uri.parse("content://media/external/audio/media/900002"))
            val updated = repository.extractEmbeddedArtworkForSongs(listOf(fresh)).single()
            assertEquals(Uri.fromFile(art), updated.artworkUri)
            assertEquals(videoFile.name, video.get(fresh)?.fileName)
            assertEquals(videoFile.name, MusicVideoRepository(context).get(fresh)?.fileName)
            val unrelated = fresh.copy(path = File(root, "unrelated.wav").path)
            assertNull(choices.read(unrelated))
            assertNull(video.get(unrelated))
            choices.save(fresh, null, true)
            assertNull(repository.extractEmbeddedArtworkForSongs(listOf(fresh)).single().artworkUri)
        } finally {
            video.deleteFile(MusicVideo("", "local", fileName = videoFile.name))
            File(context.filesDir, "music_metadata/artwork_choices/${kr.co.metadata.crawler.CatalogFileStore.hash(SongArtworkOverrides.key(original))}.json").delete()
            root.deleteRecursively()
        }
    }
    @Test fun orphanVideosCanBeRecoveredAndSharedDeletionCannotResurrectLinks() {
        val root = File(context.cacheDir, "orphan-${UUID.randomUUID()}").apply { mkdirs() }
        val ctx = isolated(root)
        try {
            val videos = File(ctx.filesDir, "music_metadata/videos").apply { mkdirs() }
            val loose = File(videos, "orphan.mp4").apply { writeText("old video") }
            val other = File(videos, "other.webm").apply { writeText("other video") }
            val repository = MusicVideoRepository(ctx)
            val orphan = repository.library().first { it.fileName == loose.name }
            val a = song(root)
            val b = a.copy(path = File(root, "b.wav").path)
            repository.attach(a, orphan); repository.attach(b, orphan)
            assertEquals(2, listOf(a, b).count { repository.get(it)?.fileName == loose.name })
            repository.deleteFile(orphan)
            assertFalse(loose.exists()); assertTrue(other.exists())
            assertNull(repository.get(a)); assertNull(repository.get(b))
            assertFalse(repository.installDownload(repository.key(a), "dQw4w9WgXcQ", orphan))
        } finally { root.deleteRecursively() }
    }
    @Test fun timingAdjustmentIsAProposalUntilAcceptanceAndPreservesOriginal() {
        val root = File(context.cacheDir, "timing-${UUID.randomUUID()}").apply { mkdirs() }
        val ctx = isolated(root)
        try {
            val track = song(root)
            val original = LyricsData(plainLyrics = "처음 가사\n다음 문장\n마지막 노래", syncedLyrics = null, source = "text file")
            val lines = listOf(TimedLine(10_000, "처음 가사"), TimedLine(40_000, "다음 문장"), TimedLine(80_000, "마지막 노래"))
            val store = EnrichmentStore(ctx)
            val proposal = store.proposal(track, "LYRICS", LocalLyricAlignment.lrc(lines), .18, "waveform_estimate", 0, original)
            assertTrue(store.propose(proposal))
            val changed = store.adjustTiming(proposal.id, lines.mapIndexed { i, line -> if (i == 1) line.copy(ms = 42_000) else line }, track.duration)
            assertEquals("PENDING", changed.state)
            assertEquals(original, changed.beforeLyrics)
            assertEquals(proposal.value, changed.predictedValue)
            assertEquals(42_000L, LocalLyricAlignment.parse(changed.value)[1].ms)
            try { store.adjustTiming(proposal.id, lines.map { it.copy(ms = 0) }, track.duration); fail("unordered timestamps") } catch (_: IllegalArgumentException) {}
            store.decide(proposal.id, "REJECTED")
            val editedOriginal = original.copy(plainLyrics = original.plainLyrics + "\n\n")
            val fresh = store.proposal(track, "LYRICS", proposal.value, .18, "waveform_estimate", 0, editedOriginal)
            assertNotEquals(proposal.id, fresh.id)
            assertTrue("Changed lyrics must bypass the old-input rejection cooldown", store.propose(fresh))
            assertEquals("PENDING", store.get(fresh.id)!!.state)
        } finally { root.deleteRecursively() }
    }
    @Test fun reportTotalsUseHalfOpenBoundariesAndActualListening() {
        val ledger = ListeningLedger.get(context)
        val key = "report-${UUID.randomUUID()}"
        val start = java.time.LocalDate.of(2001, 2, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val record = ListeningRecord(key, key, "segment", key, key, "제목", "가수", "앨범", "pop", start - 1000, start + 1000,
            1000, 100_000, 0, 1000, 1f, "pause")
        try {
            ledger.record(record)
            val window = ReportWindow(java.time.LocalDate.of(2001, 2, 1), java.time.LocalDate.of(2001, 3, 1), start, start + 86_400_000)
            val report = ListeningReports.read(ledger, window)
            assertEquals(500L, report.tracks.single { it.key == key }.listenedMs)
            assertEquals(1, report.tracks.single { it.key == key }.plays)
        } finally { ledger.writableDatabase.delete("records", "id=?", arrayOf(key)) }
    }
}
