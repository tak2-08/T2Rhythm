// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.content.Context
import androidx.annotation.Keep
import chromahub.rhythm.app.features.local.data.experience.ListeningLedger
import chromahub.rhythm.app.features.local.data.metadata.PersonalArchive
import com.google.gson.Gson
import java.io.File
import kotlin.math.abs
import kr.co.metadata.crawler.CatalogFileStore

@Keep data class TimingValidationResult(val at: Long, val songs: Int, val meanErrorMs: Double?, val previousErrorMs: Double?, val promoted: Boolean)

/** Recording-level holdout: alternate copies and the target recording cannot leak into training. */
object TimingValidation {
    private fun file(context: Context) = File(context.cacheDir, "timing-validation.json")
    fun read(context: Context): TimingValidationResult? = runCatching {
        Gson().fromJson(file(context).readText(), TimingValidationResult::class.java)
    }.getOrNull()
    fun select(context: Context, candidate: EnrichmentModel, previous: EnrichmentModel, checkpoint: () -> Unit): EnrichmentModel {
        val all = candidate.timings.distinctBy { it.pcm }
        val holdout = all.filter { CatalogFileStore.hash(it.pcm).take(4).toInt(16) % 5 == 0 }.take(8)
        val testPcm = holdout.map { it.pcm }.toSet()
        val training = TimingReferencePolicy.balanced(all.filter { it.pcm !in testPcm })
        val oldTraining = previous.timings.filter { it.pcm !in testPcm }
        val ledger = ListeningLedger.get(context)
        val errors = mutableListOf<Pair<Double, Double?>>()
        for (sample in holdout) {
            checkpoint()
            val feature = ledger.feature(sample.key)?.takeIf { it.pcmSha256 == sample.pcm } ?: continue
            fun error(examples: List<TimingTraining>): Double? {
                val guess = LocalLyricAlignment.light(sample.lines.map { it.text }, sample.durationMs,
                    "holdout:${sample.pcm}", sample.genre, feature.alignmentEnvelope, examples) ?: return null
                return guess.lines.zip(sample.lines).map { (a, b) -> abs(a.ms - b.ms).toDouble() }.average()
            }
            val nextError = error(training) ?: continue
            errors += nextError to error(oldTraining)
        }
        val nextError = errors.map { it.first }.takeIf { it.size >= 3 }?.average()
        val oldError = errors.mapNotNull { it.second }.takeIf { it.size == errors.size && it.size >= 3 }?.average()
        val promote = nextError == null || oldError == null || nextError <= oldError
        PersonalArchive.atomicWrite(file(context), Gson().toJson(TimingValidationResult(System.currentTimeMillis(), errors.size, if (promote) nextError else oldError, oldError, promote)).toByteArray())
        return candidate.copy(timings = if (promote) training else oldTraining)
    }
}
