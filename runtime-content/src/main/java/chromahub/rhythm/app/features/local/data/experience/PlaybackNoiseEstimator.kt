// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import kotlin.math.*

/** Fits gain/room response to real playback, validates on held-out microphone samples, then subtracts PCM. */
internal object PlaybackNoiseEstimator {
    data class Estimate(val residualPower: Double, val residualPeak: Double, val playbackPower: Double, val confidence: Double)

    fun estimate(microphone: ShortArray, reference: FloatArray): Estimate? {
        val count = microphone.size
        if (count < 4_000 || reference.size < count + 32 || reference.any { !it.isFinite() }) return null
        if (microphone.count { abs(it.toInt()) >= 32_440 } > count / 100) return null
        val y = DoubleArray(count) { microphone[it] / 32768.0 }
        fun correlation(start: Int, stride: Int): Double {
            var xy = 0.0; var xx = 0.0; var yy = 0.0; var sx = 0.0; var sy = 0.0; var n = 0
            for (i in 0 until count step stride) {
                val a = reference[start + i].toDouble(); val b = y[i]
                xy += a * b; xx += a * a; yy += b * b; sx += a; sy += b; n++
            }
            return (xy - sx * sy / n) / sqrt(((xx - sx * sx / n) * (yy - sy * sy / n)).coerceAtLeast(1e-18))
        }
        val maximum = reference.size - count
        var offset = 32; var best = 0.0
        // Check every sample offset: skipping lag phases misses broadband music completely.
        // Sparse time samples keep alignment bounded; the full held-out fit rejects chance matches.
        for (start in 32..maximum) {
            val value = abs(correlation(start, 64))
            if (value > best) { best = value; offset = start }
        }
        val coarse = offset
        best = 0.0
        for (start in maxOf(32, coarse - 4)..minOf(maximum, coarse + 4)) {
            val value = abs(correlation(start, 1))
            if (value > best) { best = value; offset = start }
        }
        if (best < .20) return null
        val taps = DoubleArray(32)
        val train = count * 3 / 4
        for (i in 32 until train) {
            var predicted = 0.0; var norm = 1e-8
            for (tap in taps.indices) { val x = reference[offset + i - tap].toDouble(); predicted += taps[tap] * x; norm += x * x }
            val change = .08 * (y[i] - predicted) / norm
            for (tap in taps.indices) taps[tap] += change * reference[offset + i - tap]
        }
        var testRaw = 0.0; var testResidual = 0.0; var raw = 0.0; var residual = 0.0; var peak = 0.0
        for (i in y.indices) {
            var predicted = 0.0
            for (tap in taps.indices) predicted += taps[tap] * reference[offset + i - tap]
            val error = y[i] - predicted
            raw += y[i] * y[i]; residual += error * error; peak = maxOf(peak, abs(error))
            if (i >= train) { testRaw += y[i] * y[i]; testResidual += error * error }
        }
        val improvement = 1 - testResidual / testRaw.coerceAtLeast(1e-15)
        if (!improvement.isFinite() || improvement < .04 || residual >= raw) return null
        return Estimate(residual / count, peak, (raw - residual) / count, (best * improvement).coerceIn(0.0, 1.0))
    }
}
