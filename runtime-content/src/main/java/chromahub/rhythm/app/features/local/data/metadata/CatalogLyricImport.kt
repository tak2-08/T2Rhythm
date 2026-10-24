// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.metadata

import android.content.Context
import chromahub.rhythm.app.features.local.data.discovery.LocalLyricAlignment
import chromahub.rhythm.app.features.local.di.LocalMusicModule
import chromahub.rhythm.app.shared.data.model.LyricsData
import chromahub.rhythm.app.shared.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.metadata.crawler.CatalogValue
import kr.co.metadata.crawler.CatalogField

/** Called only after the song editor's explicit Save succeeds; never by search or preview. */
object CatalogLyricImport {
    fun data(value: CatalogValue): LyricsData {
        require(value.field == CatalogField.TRACK_LYRICS && value.value.isNotBlank() && value.value.length <= 250_000)
        val text = value.value.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n')
        val lines = LocalLyricAlignment.parse(text)
        return if (lines.isNotEmpty()) LyricsData(lines.joinToString("\n") { it.text }, text, source = value.sourceUrl, isCorrected = true)
            else LyricsData(text, null, source = value.sourceUrl, isCorrected = true)
    }
    suspend fun save(context: Context, song: Song, value: CatalogValue) = withContext(Dispatchers.IO) {
        val lyrics = data(value)
        AutomaticLyricsStore.get(context).saveFetched(song, lyrics, embed = false, reviewed = true)
        LocalMusicModule.provideMusicRepository(context).updateLyricsCache(song.artist, song.title, song.id, lyrics)
    }
}
