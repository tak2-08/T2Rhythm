// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import kotlin.math.abs
import kotlin.math.roundToLong

/** Calibrate recognition latency from human timings; never train on an unreviewed prediction. */
object TimingSpeechCalibration {
    private fun median(values: List<Double>) = values.sorted().let { it[it.size / 2] }
    fun measure(truth: List<TimedLine>, recognized: TimingGuess): Pair<Double?, Double?> {
        if (truth.size != recognized.lines.size || recognized.anchors.size < 3 || recognized.anchors.size < truth.size * .75) return null to null
        fun robust(values: List<Double>): Double? {
            if (values.size < 3) return null
            val center = median(values)
            return center.takeIf { abs(it) <= 2000 && median(values.map { value -> abs(value - center) }) <= 300 }
        }
        val starts = recognized.anchors.map { truth[it].ms.toDouble() - recognized.lines[it].ms }
        val ends = recognized.anchors.mapNotNull { i -> truth[i].endMs?.let { end -> recognized.lines[i].endMs?.let { end.toDouble() - it } } }
        return robust(starts) to robust(ends)
    }
    fun apply(guess: TimingGuess, examples: List<TimingTraining>, duration: Long): TimingGuess {
        val references = TimingReferencePolicy.balanced(examples).filter { it.reviewed && it.speechOffsetMs?.isFinite() == true }
        if (references.size < 3) return guess
        val strength = references.size.toDouble() / (references.size + 6)
        val start = median(references.map { it.speechOffsetMs!! }).coerceIn(-2000.0, 2000.0) * strength
        val ends = references.mapNotNull { it.speechEndOffsetMs?.takeIf(Double::isFinite) }
        val end = if (ends.size >= 3) median(ends).coerceIn(-2000.0, 2000.0) * strength else start
        val adjusted = guess.lines.map { it.copy(ms = it.ms + start.roundToLong(), endMs = it.endMs?.plus(end.roundToLong())) }
            .let { rows -> rows.mapIndexed { i, row -> row.copy(endMs = row.endMs?.coerceAtMost(rows.getOrNull(i + 1)?.ms ?: duration)) } }
        return if (LocalLyricAlignment.valid(adjusted, duration)) guess.copy(lines = adjusted, method = "speech_anchors_calibrated") else guess
    }
}
