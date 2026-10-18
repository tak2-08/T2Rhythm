// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.content.Context
import androidx.annotation.Keep
import chromahub.rhythm.app.features.local.data.experience.ListeningLedger
import chromahub.rhythm.app.features.local.data.metadata.PersonalArchive
import com.google.gson.Gson
import java.io.File

@Keep data class TimingReview(val id: String, val example: TimingTraining, val errorMs: Double?, val at: Long,
    val sourceSignature: String? = null, val pending: Boolean = false, val calibrationAttempted: Boolean = false)

/** Durable, replaceable reviewed samples; raw audio and recognition buffers never enter this file. */
class LyricTimingFeedback(private val context: Context) {
    private val file = File(context.filesDir, "music_metadata/personalization/learning/timing-feedback.json")
    private val gson = Gson()
    fun read(): List<TimingReview> = synchronized(lock) {
        runCatching { require(file.length() in 1..8_000_000); gson.fromJson(file.readText(), Array<TimingReview>::class.java).toList() }.getOrDefault(emptyList())
    }
    fun record(proposal: EnrichmentProposal) = synchronized(lock) {
        if (proposal.kind != "LYRICS") return@synchronized
        val existing = read().filter { it.id != proposal.id }
        val next = if (proposal.state == "ACCEPTED") {
            val signature = proposal.sourceSignature.removePrefix(proposal.songKey + ":")
            val feature = ListeningLedger.get(context).feature(proposal.songKey, signature)
            val duration = proposal.sourceSignature.substringAfterLast(':').toLongOrNull() ?: return@synchronized
            val lines = LocalLyricAlignment.parse(proposal.value)
            if (!LocalLyricAlignment.valid(lines, duration) || lines.size > 400) return@synchronized
            val predicted = LocalLyricAlignment.parse(proposal.predictedValue ?: proposal.value)
            val error = if (proposal.predictedValue != null && predicted.size == lines.size) lines.zip(predicted).map { (a, b) -> kotlin.math.abs(a.ms - b.ms).toDouble() }.average() else null
            val sample = TimingTraining(proposal.songKey, feature?.pcmSha256 ?: "pending:${kr.co.metadata.crawler.CatalogFileStore.hash(proposal.sourceSignature)}",
                proposal.beforeGenre.orEmpty(), duration, lines.map { it.copy(text = it.text.take(400)) }, reviewed = true,
                audio = feature?.let(chromahub.rhythm.app.features.local.data.experience.AudioVectors::describe))
            listOf(TimingReview(proposal.id, sample, error, System.currentTimeMillis(), proposal.sourceSignature, feature == null)) +
                existing.filter { it.example.pcm != sample.pcm && it.example.key != sample.key }
        } else existing
        write(next)
    }
    fun resolve(song: chromahub.rhythm.app.shared.data.model.Song) = resolveAll(listOf(song))
    fun resolveAll(songs: List<chromahub.rhythm.app.shared.data.model.Song>) = synchronized(lock) {
        if (songs.isEmpty()) return@synchronized
        val byKey = songs.associateBy { it.uri.toString() }
        val next = read().mapNotNull { review ->
            val song = byKey[review.example.key] ?: return@mapNotNull review
            val feature = ListeningLedger.get(context).feature(song.uri.toString(), "${song.dateModified}:${song.duration}") ?: return@mapNotNull review
            if (review.sourceSignature != null && review.sourceSignature != EnrichmentStore.signature(song)) null
            else if (!review.pending && review.example.pcm != feature.pcmSha256) review
            else {
                val words = SpeechAlignment.read(context, song.uri.toString())?.takeIf { it.complete && it.signature == EnrichmentStore.signature(song) }
                val recognized = words?.let { LocalLyricAlignment.speech(review.example.lines.map { it.text }, it.words, song.duration) }
                val calibration = recognized?.let { TimingSpeechCalibration.measure(review.example.lines, it) }
                review.copy(example = review.example.copy(pcm = feature.pcmSha256,
                    audio = chromahub.rhythm.app.features.local.data.experience.AudioVectors.describe(feature), reviewed = true,
                    speechOffsetMs = calibration?.first, speechEndOffsetMs = calibration?.second), pending = false, calibrationAttempted = words != null)
            }
        }.distinctBy { it.example.pcm }
        write(next)
    }
    fun examples() = read().filter { !it.pending && !it.example.pcm.startsWith("pending:") }.map { it.example.copy(reviewed = true) }
    fun discard(id: String) = synchronized(lock) { write(read().filter { it.id != id }) }
    private fun write(next: List<TimingReview>) {
        val bounded = next.take(96).toMutableList()
        var bytes = gson.toJson(bounded).toByteArray()
        while (bytes.size > 4_000_000 && bounded.isNotEmpty()) { bounded.removeAt(bounded.lastIndex); bytes = gson.toJson(bounded).toByteArray() }
        PersonalArchive.atomicWrite(file, bytes)
        PersonalArchive.enqueue(context)
    }
    companion object { private val lock = Any() }
}
