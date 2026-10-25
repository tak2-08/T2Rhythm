// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.shared.presentation.screens

import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.util.GenreBrowseIndex
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GenreSearchStateTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private fun song(id: String, title: String, genre: String) = Song(id, title, "Artist", "Album", duration = 1000,
        uri = Uri.parse("content://test/$id"), genre = genre)

    @Test fun genreResultsSupportDrillDownAndSongsAndInvalidateWithLibraryChanges() {
        var songs by mutableStateOf(listOf(song("1", "Rock title only", "JAZZ"),
            song("2", "Quiet", "FOLK;ROCK"), song("3", "Loud", "HARD ROCK")))
        var query by mutableStateOf("록")
        var selected by mutableStateOf<String?>(null)
        var index: GenreBrowseIndex<Song>? = null
        var openedSongs = emptyList<Song>()
        compose.setContent {
            val current = rememberGenreBrowse(songs)
            SideEffect { index = current }
            MaterialTheme {
                GenreBrowseContent(current, current?.search(query).orEmpty(), selected,
                    PaddingValues(0.dp), onGenreClick = { selected = it },
                    onOpenSongs = { openedSongs = current?.songs(selected).orEmpty() })
            }
        }
        compose.waitUntil(10_000) { index != null }
        compose.onNodeWithTag("genre-result:ROCK").assertIsDisplayed().performClick()
        compose.onNodeWithTag("genre-result:HARD ROCK").assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("genre-result:FOLK"))
        compose.onNodeWithTag("genre-result:FOLK").assertIsDisplayed()
        compose.onNodeWithText("Rock title only").assertDoesNotExist()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("genre-open-songs"))
        compose.onNodeWithTag("genre-open-songs").performClick()
        compose.runOnIdle { assertEquals(listOf("2"), openedSongs.map { it.id }) }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("genre-result:HARD ROCK"))
        compose.onNodeWithTag("genre-result:HARD ROCK").performClick()
        compose.onNodeWithTag("genre-open-songs").performClick()
        compose.runOnIdle {
            assertEquals(listOf("3"), openedSongs.map { it.id })
            songs = listOf(song("4", "Updated", "INDIE ROCK")); selected = null; query = "rock"
        }
        compose.waitUntil(10_000) { index?.songs("INDIE ROCK")?.map { it.id } == listOf("4") && index?.count("ROCK") == 0 }
        compose.onNodeWithTag("genre-result:INDIE ROCK").assertIsDisplayed()
        compose.onNodeWithTag("genre-result:ROCK").assertDoesNotExist()
    }
}
