// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import chromahub.rhythm.app.shared.data.model.LyricsData

/** Providers sometimes put ordinary text in syncedLyrics. Inspect what is displayed. */
object LyricTimingInput {
    fun lines(lyrics: LyricsData?): List<String> {
        if (lyrics == null || lyrics.isErrorMessage() || !lyrics.wordByWordLyrics.isNullOrBlank()) return emptyList()
        val displayed = lyrics.getBestLyrics()?.trimStart('\uFEFF').orEmpty()
        if (LocalLyricAlignment.parse(displayed).isNotEmpty() ||
            Regex("<p\\b[^>]*\\bbegin\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(displayed)) return emptyList()
        return LocalLyricAlignment.text(displayed, null)
    }
}
