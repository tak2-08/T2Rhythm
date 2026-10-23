// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import androidx.annotation.Keep
import chromahub.rhythm.app.features.local.data.experience.AudioFeatures
import chromahub.rhythm.app.features.local.data.experience.AudioVectors
import chromahub.rhythm.app.shared.data.model.LyricsData
import java.text.Normalizer
import java.util.Locale
import kotlin.math.*

/** Independent, bounded modalities. Text is kept as feature hashes, never as a copy of the lyrics. */
@Keep data class GenreSignals(
    val version: Int = 1,
    val waveform: List<Double> = emptyList(),
    val fingerprint: List<Double> = emptyList(),
    val lyrics: List<Int> = emptyList(),
    val title: List<Int> = emptyList(),
    val pcm: String = "",
)

data class GenreSimilarity(val score: Double, val coverage: Double)

object GenreSignalFeatures {
    val weights = listOf(.50, .30, .15, .05)

    fun extract(feature: AudioFeatures, title: String, lyrics: LyricsData?): GenreSignals {
        val wave = feature.waveform.take(256).map { if (it.isFinite()) it.coerceAtLeast(0.0) else 0.0 }
        val peak = wave.maxOrNull()?.coerceAtLeast(1e-9) ?: 1.0
        val bins = if (wave.isEmpty() || wave.maxOrNull()!! <= 1e-9) emptyList() else List(32) { i ->
            val from = i * wave.size / 32; val to = ((i + 1) * wave.size / 32).coerceAtLeast(from + 1).coerceAtMost(wave.size)
            wave.subList(from.coerceAtMost(wave.lastIndex), to).average() / peak
        }
        val dynamics = if (bins.isEmpty()) emptyList() else bins + bins.zipWithNext { a, b -> abs(a - b) } + listOf(
            ((feature.peakDb - feature.rmsDb) / 30).coerceIn(0.0, 1.0),
            feature.zeroCrossingRate.coerceIn(0.0, .5) * 2,
            (feature.estimatedBpm?.div(240) ?: 0.0).coerceIn(0.0, 1.0))
        val lines = if (lyrics == null || lyrics.isErrorMessage()) "" else
            lyrics.plainLyrics?.takeIf(String::isNotBlank)
                ?: LocalLyricAlignment.parse(lyrics.syncedLyrics.orEmpty()).joinToString("\n") { it.text }.takeIf(String::isNotBlank)
                ?: lyrics.wordByWordLyrics?.takeIf { it.length <= 1_000_000 }?.let {
                    runCatching { chromahub.rhythm.app.util.RhythmLyricsParser.toPlainText(chromahub.rhythm.app.util.RhythmLyricsParser.parseWordByWordLyrics(it)) }.getOrNull()
                }.orEmpty()
        return GenreSignals(waveform = AudioVectors.normalize(dynamics),
            fingerprint = feature.acousticFingerprint.orEmpty().takeIf { it.size == 96 && it.all(Double::isFinite) }.orEmpty(),
            lyrics = text(lines, 128, 24_000), title = text(title, 32, 300),
            pcm = feature.pcmSha256.takeIf { it.matches(Regex("[a-f0-9]{64}")) }.orEmpty())
    }

    /** Frequency-capped character n-grams work for Korean and Latin text without a downloaded model. */
    fun text(raw: String, limit: Int, maxChars: Int): List<Int> {
        val normalized = Normalizer.normalize(raw.take(maxChars), Normalizer.Form.NFKC).uppercase(Locale.ROOT)
            .replace(Regex("\\[[^]\\r\\n]{0,120}]"), " ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        if (normalized.isBlank()) return emptyList()
        val counts = mutableMapOf<Int, Int>()
        normalized.split(' ').filter { it.isNotBlank() }.forEach { word ->
            val pieces = if (word.length == 1) sequenceOf(word) else sequence {
                yield(word.take(40))
                for (n in 2..3) for (i in 0..word.length - n) yield(word.substring(i, i + n))
            }
            pieces.forEach { token ->
                // Fixed hash seed; model features survive app restarts and backups.
                var hash = 0x811c9dc5.toInt()
                token.forEach { hash = (hash xor it.code) * 16777619 }
                val bucket = hash and 0xffff
                counts[bucket] = ((counts[bucket] ?: 0) + 1).coerceAtMost(4)
            }
        }
        return counts.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .take(limit).map { it.key }.sorted()
    }

    fun valid(signals: GenreSignals?) = signals != null && signals.version == 1 &&
        signals.waveform.size <= 66 && signals.fingerprint.size in setOf(0, 96) &&
        signals.waveform.all(Double::isFinite) && signals.fingerprint.all(Double::isFinite) &&
        signals.lyrics.size <= 128 && signals.title.size <= 32 &&
        (signals.lyrics + signals.title).all { it in 0..65535 }

    fun similarity(a: GenreSignals, b: GenreSignals): GenreSimilarity? {
        if (!valid(a) || !valid(b)) return null
        val parts = listOf(
            vector(a.waveform, b.waveform), vector(a.fingerprint, b.fingerprint),
            tokens(a.lyrics, b.lyrics), tokens(a.title, b.title))
        // Text alone must never manufacture a confident genre. Keep missing inputs missing.
        if (parts[0] == null) return null
        var total = 0.0; var coverage = 0.0
        parts.forEachIndexed { i, value -> if (value != null) { total += value * weights[i]; coverage += weights[i] } }
        return GenreSimilarity(total / coverage, coverage)
    }
    private fun vector(a: List<Double>, b: List<Double>): Double? =
        if (a.isEmpty() || a.size != b.size || a.sumOf { it * it } < 1e-12 || b.sumOf { it * it } < 1e-12) null
        else AudioVectors.cosine(a, b)
    private fun tokens(a: List<Int>, b: List<Int>): Double? {
        if (a.isEmpty() || b.isEmpty()) return null
        val aa = a.toSet(); val bb = b.toSet()
        return aa.intersect(bb).size.toDouble() / aa.union(bb).size
    }
}
