// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.video

import com.google.gson.Gson
import chromahub.rhythm.app.features.local.data.discovery.AudioSourceCandidate
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DownloadAlbumRecoveryTest {
    @Test fun corruptAndIncompleteFilesDoNotEscapeTheReader() {
        val file = File.createTempFile("album-test-", ".json")
        try {
            for (json in listOf("{", "null", "{}", "{\"source\":\"album\",\"tracks\":null}",
                "{\"source\":\"album\",\"title\":\"Album\",\"tracks\":[null]}")) {
                file.writeText(json)
                assertNull(json, DownloadAlbums.readAlbum(file))
            }
            file.writeText("x".repeat(512_001))
            assertNull(DownloadAlbums.readAlbum(file))
        } finally { file.delete() }
    }
    @Test fun validAlbumRetainsItsOriginalTrackOrder() {
        val file = File.createTempFile("album-test-", ".json")
        val album = DownloadAlbum("https://example.test/album", "앨범", "가수",
            listOf("second", "first").map { AudioSourceCandidate("https://example.test/$it", it, "Artist", 180) })
        try {
            file.writeText(Gson().toJson(album))
            assertEquals(album, DownloadAlbums.readAlbum(file))
        } finally { file.delete() }
    }
}
