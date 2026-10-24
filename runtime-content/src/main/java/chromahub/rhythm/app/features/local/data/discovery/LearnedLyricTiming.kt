// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import kotlin.math.*

/** A regularized timing template; one recording contributes one vote, never one vote per line. */
object LearnedLyricTiming {
    const val VERSION = "manual-intervals-2"
    private fun positions(text: List<String>): List<Double> {
        val weights = text.map { LocalLyricAlignment.normalized(it).length.coerceAtLeast(2).toDouble().pow(.65) }
        val total = weights.sum()
        var cumulative = 0.0
        return weights.map { weight -> (cumulative / total).also { cumulative += weight } }
    }
    fun fit(text: List<String>, duration: Long, genre: String, baseline: List<TimedLine>, examples: List<TimingTraining>, audio: List<Double> = emptyList()): List<TimedLine> {
        val references = TimingReferencePolicy.balanced(examples).filter { it.pcm.isNotBlank() && it.lines.size >= 3 }
            .sortedBy { sample ->
                abs(ln(sample.durationMs.toDouble() / duration)) +
                    abs(ln(sample.lines.size.toDouble() / text.size)) +
                    (if (sample.genre.equals(genre, true)) 0.0 else .3) + (if (sample.reviewed) 0.0 else .2) +
                    (if (audio.isNotEmpty() && sample.audio?.size == audio.size)
                        (1 - chromahub.rhythm.app.features.local.data.experience.AudioVectors.cosine(audio, sample.audio)) * .4 else 0.0)
            }.take(8)
        if (references.size < 3) return baseline
        val target = positions(text)
        val curves = references.map { sample ->
            val x = positions(sample.lines.map { it.text }) + 1.0
            val y = sample.lines.map { it.ms.toDouble() / sample.durationMs } +
                ((sample.lines.last().endMs ?: sample.durationMs).toDouble() / sample.durationMs)
            var right = 1
            target.map { fraction ->
                while (right < x.lastIndex && x[right] < fraction) right++
                y[right - 1] + (y[right] - y[right - 1]) *
                    ((fraction - x[right - 1]) / (x[right] - x[right - 1]).coerceAtLeast(.00001))
            }
        }
        // Strong shrinkage with little data. Median templates resist one anomalous annotation.
        val strength = (references.size.toDouble() / (references.size + 6)).coerceAtMost(.6)
        val starts = baseline.mapIndexed { i, row ->
            val learned = curves.map { it[i] }.sorted().let { it[it.size / 2] } * duration
            ((row.ms * (1 - strength) + learned * strength) / 10).roundToLong() * 10
        }
        if (starts.first() < 0 || starts.last() >= duration || starts.zipWithNext().any { it.second <= it.first }) return baseline
        val voiced = references.mapNotNull { sample ->
            val ratios = sample.lines.mapIndexedNotNull { i, row -> row.endMs?.let { end ->
                ((end - row.ms).toDouble() / ((sample.lines.getOrNull(i + 1)?.ms ?: sample.durationMs) - row.ms)).coerceIn(.1, 1.0)
            } }
            ratios.takeIf { it.size >= 3 }?.average()
        }.sorted().takeIf { it.size >= 3 }?.let { it[it.size / 2] }
        return baseline.mapIndexed { i, row ->
            val next = starts.getOrNull(i + 1) ?: duration
            row.copy(ms = starts[i], endMs = voiced?.let { (starts[i] + (next - starts[i]) * it).roundToLong().coerceIn(starts[i] + 1, next) })
        }
    }
}
