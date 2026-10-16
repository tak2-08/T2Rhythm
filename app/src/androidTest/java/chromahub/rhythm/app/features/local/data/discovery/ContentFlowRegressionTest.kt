// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.features.local.data.video.DownloadRecord
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.util.*
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kr.co.metadata.crawler.*
import org.junit.Assert.*
import org.junit.Test

class ContentFlowRegressionTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun song(
        id: String,
        title: String = "나의 노래",
        artist: String = "가수",
        album: String = "앨범",
        uri: String = "content://media/external/audio/media/1",
    ) = Song(id, title, artist, album, duration = 200_000, uri = Uri.parse(uri))

    private fun track(title: String = "나의 노래", artist: String = "가수", album: String = "앨범") =
        ChartTrack(
            1,
            Provider.MELON,
            "1",
            title,
            artist,
            album,
            "https://www.melon.com/song/detail.htm?songId=1",
        )

    @Test
    fun existingSongsUseExactIdentityAndPreferAlbumWithoutMatchingOtherEditions() {
        val original = song("one")
        val other = song("two", album = "다른 앨범")
        val index =
            PopularLibrary(
                listOf(
                    original,
                    other,
                    song("remote", title = "온라인 곡", uri = "https://example.com/a.mp3"),
                )
            )
        assertEquals(original, index.find(track()))
        assertEquals(other, index.find(track(album = "다른 앨범")))
        assertNull(index.find(track(album = "알 수 없음")))
        assertNull(index.find(track(title = "나의 노래 (Live)")))
        assertNull(index.find(track(artist = "다른 가수")))
        assertNull(index.find(track(title = "온라인 곡")))
        assertEquals(original, PopularLibrary(listOf(original)).find(track(title = "나 의 노 래")))
    }

    @Test
    fun downloadBadgesFollowIdentityAndTerminalStates() {
        val job =
            DownloadRecord(
                UUID.randomUUID().toString(),
                "AUDIO",
                "a",
                "a",
                "나의 노래",
                "가수",
                album = "앨범",
            )
        assertTrue(PopularLibrary.active(job))
        assertTrue(PopularLibrary.matches(job, track()))
        assertFalse(PopularLibrary.matches(job, track(title = "나의 노래 (Live)")))
        assertFalse(PopularLibrary.matches(job, track(album = "다른 앨범")))
        for (state in listOf("RUNNING", "INSTALLING", "ENQUEUED")) assertTrue(
            PopularLibrary.active(job.copy(state = state))
        )
        for (state in listOf("SUCCEEDED", "FAILED", "CANCELLED")) assertFalse(
            PopularLibrary.active(job.copy(state = state))
        )
    }

    @Test
    fun interruptedDownloadsReturnToWaitingUntilTheirWorkerActuallyResumes() {
        val id = UUID.randomUUID()
        val record =
            DownloadRecord(
                id.toString(),
                "MODEL",
                "ko",
                "ko",
                "Korean model",
                state = "INSTALLING",
                bytes = 100,
                totalBytes = 100,
            )
        val queued = androidx.work.WorkInfo(id, androidx.work.WorkInfo.State.ENQUEUED, emptySet())
        val running = androidx.work.WorkInfo(id, androidx.work.WorkInfo.State.RUNNING, emptySet())
        assertEquals(
            "ENQUEUED",
            chromahub.rhythm.app.features.local.data.video.DownloadRegistry.merge(record, queued)
                .state,
        )
        assertEquals(
            "INSTALLING",
            chromahub.rhythm.app.features.local.data.video.DownloadRegistry.merge(record, running)
                .state,
        )
        assertEquals(
            "CANCELLED",
            chromahub.rhythm.app.features.local.data.video.DownloadRegistry.merge(
                    record.copy(state = "CANCELLED"),
                    queued,
                )
                .state,
        )
        assertEquals(
            "SUCCEEDED",
            chromahub.rhythm.app.features.local.data.video.DownloadRegistry.merge(
                    record.copy(state = "SUCCEEDED"),
                    queued,
                )
                .state,
        )
        val registry = chromahub.rhythm.app.features.local.data.video.DownloadRegistry
        val file = File(context.filesDir, "music_metadata/downloads/${record.id}.json")
        try {
            registry.save(context, record.copy(state = "CANCELLED"))
            registry.update(context, record.id) { it.copy(state = "RUNNING", bytes = 101) }
            assertEquals("CANCELLED", registry.get(context, record.id)?.state)
            assertEquals(100L, registry.get(context, record.id)?.bytes)
        } finally {
            file.delete()
        }
    }

    private fun paint(file: File, color: Int) {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun pixel(uri: Uri): Int {
        val loaded =
            context.imageLoader.execute(
                ImageRequest.Builder(context).data(uri).allowHardware(false).size(24).build()
            ) as SuccessResult
        return loaded.drawable.toBitmap().getPixel(12, 12)
    }

    @Test
    fun samePathEditsAndAppCacheClearRebuildArtworkWithoutDeletingOriginals() = runBlocking {
        val root =
            File(context.filesDir, "cache-regression-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val source = File(root, "source.png")
            val target = File(root, "artwork.webp")
            paint(source, Color.RED)
            val uri = Uri.fromFile(source)
            assertEquals(Color.RED, pixel(uri))
            ArtworkStorage.copy(context, uri, target, true)
            assertTrue(Color.red(pixel(Uri.fromFile(target))) > 200)
            paint(source, Color.BLUE)
            // Same source and destination paths were both previously cached.
            ArtworkStorage.copy(context, uri, target, true)
            val old = ArtworkInvalidation.version(Uri.fromFile(target))
            ArtworkInvalidation.invalidate(context)
            assertNotEquals(old, ArtworkInvalidation.version(Uri.fromFile(target)))
            assertTrue(Color.blue(pixel(Uri.fromFile(target))) > 200)
            paint(source, Color.GREEN)
            CacheManager.clearAllCache(context)
            assertEquals(Color.GREEN, pixel(uri))
            assertTrue(source.isFile)
            assertTrue(target.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stableStreamingUrisResolveDirectAudioWithoutStartingDownloads() = runBlocking {
        val source =
            AudioSourceCandidate("https://example.com/a.mp3", "source", "source artist", 201)
        val value = StreamingAudio.song(track(), source)
        assertEquals(source.url, value.uri.toString())
        assertEquals(201_000L, value.duration)
        assertEquals("나의 노래", value.title)
        val spec = androidx.media3.datasource.DataSpec(Uri.parse(source.url))
        assertSame(spec, StreamingAudio.resolve(spec))
    }

    @Test
    fun reviewedLyricsReplaceSyncedVersionsAndSurviveLateProviderResponses() = runBlocking {
        val catalog =
            chromahub.rhythm.app.features.local.data.metadata.CatalogEnrichmentService.get(context)
        val options = catalog.preferences.options.value
        val song = song("lyrics-" + UUID.randomUUID(), title = "캐시 검증 " + UUID.randomUUID())
        val store =
            chromahub.rhythm.app.features.local.data.metadata.AutomaticLyricsStore.get(context)
        val key = CatalogFileStore.hash(catalog.songKey(song))
        try {
            catalog.preferences.update(options.copy(autoLyrics = false, embedLyrics = false))
            val edited =
                chromahub.rhythm.app.shared.data.model.LyricsData(
                    plainLyrics = "직접 수정한 가사",
                    syncedLyrics = null,
                    source = "Local File",
                )
            store.saveFetched(song, edited, embed = false, reviewed = true)
            assertEquals(edited, store.read(song))
            catalog.preferences.update(options.copy(autoLyrics = true, embedLyrics = false))
            store.saveFetched(
                song,
                chromahub.rhythm.app.shared.data.model.LyricsData(
                    plainLyrics = "이전 조회 결과",
                    syncedLyrics = "[00:01.00]이전 조회 결과",
                ),
                embed = false,
            )
            assertEquals(edited, store.read(song))
            assertFalse(File(store.root, "$key.lrc").exists())
            assertEquals("직접 수정한 가사", File(store.root, "$key.txt").readText())
        } finally {
            catalog.preferences.update(options)
            store.root
                .listFiles()
                ?.filter { it.name.startsWith(key + ".") }
                ?.forEach { it.delete() }
            context
                .getSharedPreferences("catalog_lyrics_writes", Context.MODE_PRIVATE)
                .edit()
                .remove("reviewed:$key")
                .commit()
        }
    }

    @Test
    fun speechModelInstallsCompletelyWhileUnpluggedAndLeavesVerifiedModel() = runBlocking {
        val archive = File(context.getExternalFilesDir(null), "vosk-test-model.zip")
        org.junit.Assume.assumeTrue(archive.isFile)
        val temp =
            File(context.noBackupFilesDir, "install-regression-" + UUID.randomUUID()).apply {
                mkdirs()
            }
        val isolated =
            object : ContextWrapper(context) {
                override fun getNoBackupFilesDir() = temp

                override fun getApplicationContext(): Context = this
            }
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) {
            ui.executeShellCommand(command).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
        }
        try {
            shell("dumpsys battery unplug")
            shell("dumpsys battery set status 3")
            // BatteryStats.isCharging may lag the sticky battery broadcast on emulators.
            // The old analysis gate used EXTRA_PLUGGED, so verify that exact condition.
            fun plugged() =
                context
                    .registerReceiver(
                        null,
                        android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
                    )
                    ?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1)
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            while (plugged() != 0 && android.os.SystemClock.elapsedRealtime() < deadline) delay(100)
            assertEquals(0, plugged())
            withTimeout(600_000) { SpeechModelStore.install(isolated, archive) }
            assertTrue(SpeechModelStore.installed(isolated))
            org.vosk.Model(SpeechModelStore.directory(isolated).path).close()
        } finally {
            shell("dumpsys battery reset")
            temp.deleteRecursively()
        }
    }
}
