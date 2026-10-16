// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import chromahub.rhythm.app.shared.data.model.Song

/** A delayed technical probe must never restore old tags, covers, deleted or moved files. */
fun mergeAudioMetadata(current: List<Song>, extracted: List<Song>): List<Song> {
    val byId = extracted.associateBy { it.id }
    return current.map { song ->
        val info = byId[song.id]
        if (info == null || info.uri != song.uri) song
        else
            song.copy(
                bitrate = info.bitrate ?: song.bitrate,
                sampleRate = info.sampleRate ?: song.sampleRate,
                channels = info.channels ?: song.channels,
                codec = info.codec ?: song.codec,
            )
    }
}

/** Only fill a genre if that field and file identity still match the probe's input. */
fun mergeDetectedGenres(
    current: List<Song>,
    original: List<Song>,
    detected: List<Song>,
): List<Song> {
    val before = original.associateBy { it.id }
    val results = detected.associateBy { it.id }
    return current.map { song ->
        val source = before[song.id]
        val result = results[song.id]
        if (
            source == null ||
                result == null ||
                song.uri != source.uri ||
                result.uri != source.uri ||
                song.genre != source.genre
        )
            song
        else song.copy(genre = result.genre)
    }
}
