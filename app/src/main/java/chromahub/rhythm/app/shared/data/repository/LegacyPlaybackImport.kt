// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.shared.data.repository

import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader

/** Keeps only one bounded batch in memory, including when upgrading a years-long history. */
internal object LegacyPlaybackImport {
    const val BATCH_SIZE = 128

    fun read(input: Reader, consume: (List<PlaybackStatsRepository.PlaybackEvent>) -> Unit) {
        val adapter = Gson().getAdapter(PlaybackStatsRepository.PlaybackEvent::class.java)
        val buffered = input.buffered()
        // JsonReader throws EOF for an empty document. Old installations may have an empty file.
        while (true) {
            buffered.mark(1)
            val first = buffered.read()
            if (first == -1) return
            if (first.toChar() !in " \t\r\n") {
                buffered.reset()
                break
            }
        }
        JsonReader(buffered).use { reader ->
            reader.beginArray()
            val batch = ArrayList<PlaybackStatsRepository.PlaybackEvent>(BATCH_SIZE)
            while (reader.hasNext()) {
                val event = requireNotNull(adapter.read(reader)) { "Null legacy event" }
                require(
                    !event.songId.isNullOrBlank() &&
                        event.durationMs > 0 &&
                        event.startMillis() >= 0 &&
                        event.endMillis() >= event.startMillis()
                ) {
                    "Invalid legacy playback interval"
                }
                batch.add(event)
                if (batch.size == BATCH_SIZE) {
                    consume(batch.toList())
                    batch.clear()
                }
            }
            reader.endArray()
            check(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing legacy history data" }
            if (batch.isNotEmpty()) consume(batch)
        }
    }
}
