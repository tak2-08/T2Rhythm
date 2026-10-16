// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.features.local.data.database.RhythmDatabase
import chromahub.rhythm.app.features.local.data.database.entity.toEntity
import chromahub.rhythm.app.features.local.data.database.entity.toSong
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.util.mergeAudioMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MetadataEditRaceTest {
    @Test
    fun delayedProbePreservesEditedTagsArtworkMembershipAndDatabaseRows() = runBlocking {
        val original =
            Song(
                "race",
                "Old title",
                "Old artist",
                "Old album",
                duration = 200000,
                uri = Uri.parse("content://test/audio/1"),
                artworkUri = Uri.parse("file:///old.webp"),
            )
        val edited =
            original.copy(
                title = "직접 수정한 제목",
                artist = "직접 수정한 가수",
                album = "새 앨범",
                artworkUri = Uri.parse("file:///new.webp"),
                genre = "Rock",
                year = 2026,
            )
        val extracted =
            original.copy(bitrate = 320000, sampleRate = 48000, channels = 2, codec = "FLAC")
        val added = edited.copy(id = "added")
        val merged =
            mergeAudioMetadata(
                listOf(edited, added),
                listOf(extracted, original.copy(id = "deleted")),
            )
        assertEquals(listOf("race", "added"), merged.map { it.id })
        assertEquals(
            edited.copy(bitrate = 320000, sampleRate = 48000, channels = 2, codec = "FLAC"),
            merged[0],
        )
        assertEquals(added, merged[1])
        val moved = edited.copy(uri = Uri.parse("content://test/audio/2"))
        assertEquals(listOf(moved), mergeAudioMetadata(listOf(moved), listOf(extracted)))
        val db =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    RhythmDatabase::class.java,
                )
                .build()
        try {
            val dao = db.songDao()
            dao.upsertAll(listOf(edited.toEntity()))
            assertEquals(
                1,
                dao.updateAudioMetadata(
                    extracted.id,
                    extracted.uri.toString(),
                    extracted.bitrate,
                    extracted.sampleRate,
                    extracted.channels,
                    extracted.codec,
                ),
            )
            assertEquals(merged[0], dao.getSongById("race")!!.toSong())
            assertEquals(
                0,
                dao.updateAudioMetadata("deleted", extracted.uri.toString(), 1, 1, 1, "wrong"),
            )
            assertEquals(
                0,
                dao.updateAudioMetadata(extracted.id, moved.uri.toString(), 1, 1, 1, "wrong"),
            )
            assertEquals(1, dao.getCount())
            val genres = listOf(original.copy(genre = "Pop"))
            assertEquals(
                listOf(edited),
                chromahub.rhythm.app.util.mergeDetectedGenres(
                    listOf(edited),
                    listOf(original),
                    genres,
                ),
            )
            assertEquals(
                "Pop",
                chromahub.rhythm.app.util
                    .mergeDetectedGenres(listOf(original), listOf(original), genres)
                    .single()
                    .genre,
            )
            assertEquals(
                0,
                dao.updateDetectedGenre(original.id, original.uri.toString(), null, "Pop"),
            )
            assertEquals("Rock", dao.getSongById(original.id)?.genre)
        } finally {
            db.close()
        }
    }
}
