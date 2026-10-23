// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.shared.presentation.screens

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import chromahub.rhythm.app.shared.data.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LocalSearchStateTest {
    @get:Rule val compose = createComposeRule()

    private fun song(title: String, genre: String? = null) =
        Song(
            title,
            title,
            "김광석",
            "앨범",
            duration = 180000,
            uri = Uri.parse("content://test/$title"),
            genre = genre,
        )

    @Test
    fun rapidQueryChangesNeverShowOldResultsAndSourceEditsInvalidateTheIndex() {
        val source = mutableStateOf(listOf(song("서른 즈음에"), song("이등병의 편지")))
        val query = mutableStateOf("ㅅㄹㅈㅇㅇ")
        val enabled = mutableStateOf(true)
        compose.setContent {
            val state =
                rememberLocalSearch(
                    query.value,
                    source.value,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    enabled.value,
                    false,
                    false,
                    false,
                )
            Text(
                if (state.searching) "loading"
                else state.results.songs.joinToString { it.title }.ifEmpty { "empty" }
            )
        }
        compose.waitUntil(30000) {
            compose.onAllNodesWithText("서른 즈음에").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { query.value = "이등병" }
        compose.onNodeWithText("서른 즈음에").assertDoesNotExist()
        compose.waitUntil(30000) {
            compose.onAllNodesWithText("이등병의 편지").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { source.value = listOf(song("새로운 제목")) }
        compose.onNodeWithText("이등병의 편지").assertDoesNotExist()
        compose.waitUntil(30000) {
            compose.onAllNodesWithText("empty").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { query.value = "새로운" }
        compose.waitUntil(30000) {
            compose.onAllNodesWithText("새로운 제목").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithText("새로운 제목").assertDoesNotExist()
        compose.waitUntil(30000) {
            compose.onAllNodesWithText("empty").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun albumsSearchTrackTitlesAndGenreCountsAreCaseInsensitivePerSong() {
        val songs = listOf(song("서른 즈음에", "Folk;FOLK"), song("다른 노래", "folk, Pop"))
        val albums = listOf(Album("album", "다시 듣기", "김광석", songs = songs))
        val artists = listOf(Artist("artist", "김광석"))
        val playlists = listOf(Playlist("playlist", "나의 음악"))
        var result: LocalSearchState? = null
        compose.setContent {
            result =
                rememberLocalSearch(
                    "ㅅㄹㅈㅇㅇ",
                    songs,
                    albums,
                    artists,
                    playlists,
                    true,
                    true,
                    true,
                    true,
                )
            Text(if (result!!.searching) "loading" else "ready")
        }
        compose.waitUntil(30000) {
            compose.onAllNodesWithText("ready").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertEquals("다시 듣기", result!!.results.albums.single().title)
            assertEquals(2, result!!.genreCounts["FOLK"])
            assertEquals(1, result!!.genreCounts["POP"])
            assertTrue(result!!.results.artists.isEmpty())
            assertTrue(result!!.results.playlists.isEmpty())
        }
    }
}
