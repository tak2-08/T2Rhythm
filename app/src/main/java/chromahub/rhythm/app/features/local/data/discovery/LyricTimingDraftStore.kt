// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.content.Context
import androidx.annotation.Keep
import chromahub.rhythm.app.features.local.data.metadata.PersonalArchive
import chromahub.rhythm.app.shared.data.model.LyricsData
import chromahub.rhythm.app.shared.data.model.Song
import com.google.gson.Gson
import java.io.File
import kr.co.metadata.crawler.CatalogFileStore

@Keep data class LyricTimingDraft(val signature: String, val original: LyricsData, val proposalId: String?,
    val rows: List<LyricTimingMark>, val selected: Int = 0, val updatedAt: Long = System.currentTimeMillis(),
    val inputLyrics: LyricsData? = null)

/** Small persistent user drafts, separate from the original lyrics and disposable analysis caches. */
class LyricTimingDraftStore(context: Context) {
    private val root = File(context.filesDir, "music_metadata/personalization/timing-drafts").apply { mkdirs() }
    private fun file(song: Song) = File(root, CatalogFileStore.hash(song.uri.toString()) + ".json")
    fun read(song: Song, original: LyricsData, proposalId: String?, inputLyrics: LyricsData = original): LyricTimingDraft? = synchronized(lock) {
        runCatching {
            val file = file(song); require(file.length() in 1..1_000_000)
            Gson().fromJson(file.readText(), LyricTimingDraft::class.java).takeIf {
                it.signature == EnrichmentStore.signature(song) && it.proposalId == proposalId &&
                    EnrichmentStore.sameLyrics(it.original, original) &&
                    EnrichmentStore.sameLyrics(it.inputLyrics ?: it.original, inputLyrics) && ManualLyricTiming.valid(it.rows, song.duration)
            }
        }.getOrNull()
    }
    fun save(song: Song, draft: LyricTimingDraft) = synchronized(lock) {
        require(draft.signature == EnrichmentStore.signature(song) && ManualLyricTiming.valid(draft.rows, song.duration))
        val bytes = Gson().toJson(draft).toByteArray(); require(bytes.size <= 1_000_000)
        PersonalArchive.atomicWrite(file(song), bytes)
        // A draft is authored user data. Keep one per song; never evict another unfinished song.
    }
    fun delete(song: Song) = synchronized(lock) { file(song).delete() }
    companion object { private val lock = Any() }
}
