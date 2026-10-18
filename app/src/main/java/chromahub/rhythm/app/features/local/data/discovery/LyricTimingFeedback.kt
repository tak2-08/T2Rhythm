// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.content.Context
import androidx.annotation.Keep
import chromahub.rhythm.app.features.local.data.experience.ListeningLedger
import chromahub.rhythm.app.features.local.data.metadata.PersonalArchive
import com.google.gson.Gson
import java.io.File

@Keep data class TimingReview(val id: String, val example: TimingTraining, val errorMs: Double?, val at: Long)

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
            val feature = ListeningLedger.get(context).feature(proposal.songKey) ?: return@synchronized
            val duration = feature.decodedDurationMs
            val lines = LocalLyricAlignment.parse(proposal.value)
            if (!LocalLyricAlignment.valid(lines, duration) || lines.size > 400) return@synchronized
            val predicted = LocalLyricAlignment.parse(proposal.predictedValue ?: proposal.value)
            val error = if (proposal.predictedValue != null && predicted.size == lines.size) lines.zip(predicted).map { (a, b) -> kotlin.math.abs(a.ms - b.ms).toDouble() }.average() else null
            val sample = TimingTraining(proposal.songKey, feature.pcmSha256, proposal.beforeGenre.orEmpty(), duration, lines.map { it.copy(text = it.text.take(400)) })
            listOf(TimingReview(proposal.id, sample, error, System.currentTimeMillis())) + existing.filter { it.example.pcm != sample.pcm }
        } else existing
        val bounded = next.take(96).toMutableList()
        var bytes = gson.toJson(bounded).toByteArray()
        while (bytes.size > 4_000_000 && bounded.isNotEmpty()) { bounded.removeAt(bounded.lastIndex); bytes = gson.toJson(bounded).toByteArray() }
        PersonalArchive.atomicWrite(file, bytes)
        PersonalArchive.enqueue(context)
    }
    companion object { private val lock = Any() }
}
