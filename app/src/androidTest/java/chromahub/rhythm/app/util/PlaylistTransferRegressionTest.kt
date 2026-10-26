// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.shared.data.model.Playlist
import chromahub.rhythm.app.shared.data.model.Song
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

class PlaylistTransferRegressionTest {
    @Test fun zipKeepsKoreanNamesAndEverySameNamedPlaylist() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "playlist-export-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val lists = listOf(Playlist("one", "내 음악"), Playlist("two", "내 음악"), Playlist("three", "다른 음악"))
            val file = PlaylistImportExportUtils.exportAllPlaylists(context, lists,
                PlaylistImportExportUtils.PlaylistExportFormat.JSON, outputDirectory = directory).getOrThrow()
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().toList()
                assertEquals(3, entries.size)
                assertEquals(3, entries.map { it.name }.toSet().size)
                assertTrue(entries.any { it.name == "내 음악.json" })
                val ids = entries.map { entry ->
                    val json = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    org.json.JSONObject(json).getString("id")
                }.toSet()
                assertEquals(setOf("one", "two", "three"), ids)
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun importsKeepDifferentRecordingsWithTheSameTitleAndArtist() {
        val songs = listOf("studio", "live").map { id -> Song(id, "노래", "가수", "앨범", duration = 180000,
            uri = Uri.parse("content://test/$id")) }
        for (methodName in listOf("importFromM3u", "importFromPls")) {
            val content = if (methodName.endsWith("M3u")) songs.joinToString("\n") { it.uri.toString() }
                else "file1=${songs[0].uri}\nfile2=${songs[1].uri}"
            val method = PlaylistImportExportUtils::class.java.getDeclaredMethod(methodName,
                String::class.java, String::class.java, List::class.java, Map::class.java, String::class.java)
                .apply { isAccessible = true }
            val imported = method.invoke(PlaylistImportExportUtils, content, "imported.m3u", songs,
                emptyMap<String, Song>(), null) as Playlist
            assertEquals(songs, imported.songs)
        }
    }
}
