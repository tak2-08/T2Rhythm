// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.metadata

import kr.co.metadata.crawler.CatalogIdentity
import kotlin.math.max

/** Similarity describes metadata, not a probability that two recordings sound identical. */
internal object ArtworkMatchPolicy {
    private val variants = listOf("live", "cover", "remix", "instrumental", "karaoke", "sped up", "slowed", "라이브", "커버", "반주")
    private val decoration = Regex("(?i)\\b(official|music video|audio|lyrics?|visualizer|video|mv|m/v|hd|hq|4k|1080p|720p)\\b|공식|뮤직비디오|가사")
    private fun normalized(value: String) = CatalogIdentity.normalize(value).take(400)
    private fun similarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        var row = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val next = IntArray(b.length + 1); next[0] = i + 1
            for (j in b.indices) next[j + 1] = minOf(next[j] + 1, row[j + 1] + 1, row[j] + if (a[i] == b[j]) 0 else 1)
            row = next
        }
        return 1.0 - row[b.length].toDouble() / max(a.length, b.length)
    }
    private fun edition(value: String) = variants.filter { Regex("(?i)(?<![a-z])${Regex.escape(it)}(?![a-z])").containsMatchIn(value) }.toSet()
    fun score(title: String, artist: String, candidateTitle: String, candidateArtist: String, youtube: Boolean = false): Double {
        if (CatalogIdentity.missing(title) || CatalogIdentity.missing(artist) || edition(title) != edition(candidateTitle)) return 0.0
        var cleaned = if (youtube) candidateTitle.replace(decoration, " ").trim(' ', '-', '|', '[', ']', '(', ')') else candidateTitle
        var creditedArtist: String? = null
        if (youtube) {
            val parts = cleaned.split(Regex("\\s+[-–—:]\\s+"), limit = 2)
            if (parts.size == 2 && CatalogIdentity.artistMatches(artist, parts[0])) {
                creditedArtist = parts[0]; cleaned = parts[1]
            }
            Regex("['‘\"“]([^'’\"”]+)['’\"”]").find(cleaned)?.let { quoted ->
                val prefix = cleaned.substring(0, quoted.range.first).trim()
                if (CatalogIdentity.artistMatches(artist, prefix)) { creditedArtist = prefix; cleaned = quoted.groupValues[1] }
            }
        }
        val artistName = if (youtube) candidateArtist.replace(Regex("(?i)\\s*(?:-\\s*topic|vevo|official(?: channel)?)$"), "").trim() else candidateArtist
        val artistScore = if (CatalogIdentity.artistMatches(artist, artistName) || creditedArtist?.let { CatalogIdentity.artistMatches(artist, it) } == true) 1.0
            else similarity(normalized(artist), normalized(artistName))
        return minOf(similarity(normalized(title), normalized(cleaned)), artistScore)
    }
    fun accepts(title: String, artist: String, candidateTitle: String, candidateArtist: String, threshold: Int,
        durationMs: Long = 0, candidateDurationMs: Long? = null, tolerance: Double = 3.0, youtube: Boolean = false): Boolean =
        score(title, artist, candidateTitle, candidateArtist, youtube) * 100 + 1e-8 >= threshold.coerceIn(90, 100) &&
            (durationMs <= 0 || CatalogIdentity.durationMatches(durationMs, candidateDurationMs, tolerance))
}
