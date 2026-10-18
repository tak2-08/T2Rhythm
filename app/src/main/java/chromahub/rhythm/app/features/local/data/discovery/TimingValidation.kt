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

@Keep data class TimingValidationResult(val at: Long, val songs: Int, val meanErrorMs: Double?, val previousErrorMs: Double?, val promoted: Boolean,
    val method: String? = null)

/** Recording-level holdout: alternate copies and the target recording cannot leak into training. */
object TimingValidation {
    private fun file(context: Context) = File(context.cacheDir, "timing-validation.json")
    fun read(context: Context): TimingValidationResult? = runCatching {
        Gson().fromJson(file(context).readText(), TimingValidationResult::class.java)
    }.getOrNull()
    fun select(context: Context, candidate: EnrichmentModel, previous: EnrichmentModel, checkpoint: () -> Unit): EnrichmentModel {
        val all = TimingReferencePolicy.balanced(candidate.timings)
        val fixed = all.filter { CatalogFileStore.hash(it.pcm).take(4).toInt(16) % 5 == 0 }.take(8)
        val independent = fixed.size >= 3 && all.size - fixed.size >= 3
        val holdout = if (independent) fixed else all.sortedBy { CatalogFileStore.hash(it.pcm) }.take(12)
        val testPcm = holdout.map { it.pcm }.toSet()
        val training = TimingReferencePolicy.balanced(all.filter { !independent || it.pcm !in testPcm })
        val oldTraining = previous.timings.filter { !independent || it.pcm !in testPcm }
        val ledger = ListeningLedger.get(context)
        val errors = mutableListOf<Pair<Double, Double?>>()
        val speechErrors = mutableListOf<Pair<Double, Double?>>()
        for (sample in holdout) {
            checkpoint()
            val feature = ledger.feature(sample.key)?.takeIf { it.pcmSha256 == sample.pcm } ?: continue
            fun distance(guess: TimingGuess): Double {
                return guess.lines.zip(sample.lines).mapIndexed { index, (a, b) ->
                    val endError = b.endMs?.let { abs((a.endMs ?: guess.lines.getOrNull(index + 1)?.ms ?: sample.durationMs) - it).toDouble() }
                    if (endError != null) (abs(a.ms - b.ms) + endError) / 2 else abs(a.ms - b.ms).toDouble()
                }.average()
            }
            fun error(examples: List<TimingTraining>): Double? {
                val guess = LocalLyricAlignment.light(sample.lines.map { it.text }, sample.durationMs,
                    "holdout:${sample.pcm}", sample.genre, feature.alignmentEnvelope, examples.filter { it.pcm != sample.pcm }, sample.audio.orEmpty()) ?: return null
                return distance(guess)
            }
            val nextError = error(training) ?: continue
            errors += nextError to error(oldTraining)
            val speech = SpeechAlignment.read(context, sample.key)?.takeIf { it.complete && it.signature == "${sample.key}:${feature.sourceSignature}" }
            val raw = speech?.let { LocalLyricAlignment.speech(sample.lines.map { line -> line.text }, it.words, sample.durationMs) }
            if (raw != null) speechErrors += distance(TimingSpeechCalibration.apply(raw, training.filter { it.pcm != sample.pcm }, sample.durationMs)) to
                distance(TimingSpeechCalibration.apply(raw, oldTraining.filter { it.pcm != sample.pcm }, sample.durationMs))
        }
        val nextError = errors.map { it.first }.takeIf { it.size >= 4 || independent && it.size >= 3 }?.average()
        val oldError = errors.mapNotNull { it.second }.takeIf { it.size == errors.size && nextError != null }?.average()
        val promote = nextError != null && oldError != null && accepts(errors)
        val speechValidated = speechErrors.size >= 3 && accepts(speechErrors)
        val selected = (if (promote) training else oldTraining.ifEmpty { training.take(2) }).map { row ->
            if (promote && !speechValidated) {
                val prior = oldTraining.firstOrNull { it.pcm == row.pcm }
                row.copy(speechOffsetMs = prior?.speechOffsetMs, speechEndOffsetMs = prior?.speechEndOffsetMs)
            } else row
        }
        PersonalArchive.atomicWrite(file(context), Gson().toJson(TimingValidationResult(System.currentTimeMillis(), errors.size,
            if (promote) nextError else oldError, oldError, promote,
            if (nextError == null) "insufficient" else if (independent) "holdout" else "recording_cross_validation")).toByteArray())
        return candidate.copy(timings = selected, timingRevision = TimingReferencePolicy.revision(selected))
    }

    /** A lower mean alone cannot hide a large regression on one recording. */
    fun accepts(errors: List<Pair<Double, Double?>>): Boolean {
        if (errors.size < 3 || errors.any { !it.first.isFinite() || it.second?.isFinite() != true }) return false
        return errors.map { it.first }.average() <= errors.map { it.second!! }.average() &&
            errors.all { (next, old) -> next <= old!! * 1.2 + 250 }
    }
}
