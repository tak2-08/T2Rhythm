// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.video

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DownloadPlaybackRegressionTest {
    @Test fun completedAudioPlaysWithoutWaitingForLibraryScanAndMissingFilesAreRejected() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "playback-${UUID.randomUUID()}.m4a")
        var uri: android.net.Uri? = null
        val record = DownloadRecord(UUID.randomUUID().toString(), "AUDIO", "test", "test-source-${UUID.randomUUID()}", "완료된 음악", "가수", album = "앨범", service = "DIRECT")
        try {
            instrumentation.context.assets.open("audio-test.m4a").use { input -> source.outputStream().use { input.copyTo(it) } }
            DownloadRegistry.save(context, record)
            val published = DownloadedAudioLibrary.publish(context, record, source, "m4a", 1000)
            uri = published.first
            val completed = record.copy(state = "SUCCEEDED", uri = uri.toString(), fileBytes = published.second)
            DownloadRegistry.save(context, completed)
            val song = DownloadPlayback.song(context, completed, emptyList())!!
            assertEquals(uri, song.uri)
            assertTrue(song.duration > 0)
            assertEquals(record.title, song.title)
            assertNull(DownloadPlayback.song(context, completed.copy(state = "FAILED"), emptyList()))
            assertNull(DownloadPlayback.song(context, completed.copy(uri = "content://media/external/audio/media/0"), emptyList()))
            val librarySong = song.copy(artworkUri = android.net.Uri.parse("file:///test.webp"))
            assertEquals(librarySong, DownloadPlayback.song(context, completed, listOf(librarySong)))
        } finally {
            uri?.let { context.contentResolver.delete(it, null, null) }
            File(context.filesDir, "music_metadata/downloads/${record.id}.json").delete()
            source.delete()
        }
    }
}
