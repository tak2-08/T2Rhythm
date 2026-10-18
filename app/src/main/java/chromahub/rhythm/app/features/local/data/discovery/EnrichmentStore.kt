// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.content.Context
import androidx.annotation.Keep
import chromahub.rhythm.app.features.local.data.metadata.PersonalArchive
import chromahub.rhythm.app.shared.data.model.LyricsData
import chromahub.rhythm.app.shared.data.model.Song
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kr.co.metadata.crawler.CatalogFileStore

@Keep
data class EnrichmentProposal(
    val id: String,
    val kind: String,
    val songKey: String,
    val title: String,
    val artist: String,
    val sourceSignature: String,
    val value: String,
    val confidence: Double,
    val method: String,
    val evidence: Int,
    val beforeGenre: String? = null,
    val beforeLyrics: LyricsData? = null,
    val examples: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val state: String = "PENDING",
    val decidedAt: Long = 0,
    val predictedValue: String? = null,
)

@Keep
data class EnrichmentModel(
    val version: Int = 1,
    val examples: List<GenreExample> = emptyList(),
    val timings: List<TimingTraining> = emptyList(),
)

class EnrichmentStore(private val context: Context) {
    companion object {
        val revision = MutableStateFlow(0L)
        private val lock = Any()

        fun signature(song: Song) = "${song.uri}:${song.dateModified}:${song.duration}"
        fun sameLyrics(a: LyricsData?, b: LyricsData?) = a?.plainLyrics == b?.plainLyrics &&
            a?.syncedLyrics == b?.syncedLyrics && a?.wordByWordLyrics == b?.wordByWordLyrics
    }

    private val gson = Gson()
    private val root =
        File(context.filesDir, "music_metadata/personalization/learning").apply { mkdirs() }
    private val proposals = File(root, "proposals").apply { mkdirs() }

    fun model(): EnrichmentModel =
        runCatching {
                val f = File(context.cacheDir, "enrichment-model.json")
                require(f.length() in 1..8_000_000)
                gson.fromJson(f.readText(), EnrichmentModel::class.java)
            }
            .getOrNull() ?: EnrichmentModel()

    fun saveModel(model: EnrichmentModel) {
        PersonalArchive.atomicWrite(
            File(context.cacheDir, "enrichment-model.json"),
            gson.toJson(model).toByteArray(),
        )
        revision.value++
    }

    fun get(id: String): EnrichmentProposal? =
        runCatching {
                require(id.matches(Regex("[a-f0-9]{64}")))
                val f = File(proposals, "$id.json")
                require(f.length() in 1..2_000_000)
                gson.fromJson(f.readText(), EnrichmentProposal::class.java)
            }
            .getOrNull()

    fun list(limit: Int = 200): List<EnrichmentProposal> =
        proposals
            .listFiles()
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .take(limit)
            .mapNotNull { get(it.nameWithoutExtension) }
            .sortedByDescending { it.createdAt }

    fun propose(proposal: EnrichmentProposal, updateHints: Boolean = true): Boolean =
        synchronized(lock) {
            if (get(proposal.id) != null) return@synchronized false
            val pointer =
                File(root, "current-${CatalogFileStore.hash(proposal.songKey+proposal.kind)}")
            val old = runCatching { get(pointer.readText()) }.getOrNull()
            val sameInput = old?.sourceSignature == proposal.sourceSignature &&
                (proposal.kind != "LYRICS" || sameLyrics(old.beforeLyrics, proposal.beforeLyrics))
            if (
                old?.state == "REJECTED" &&
                    sameInput &&
                    System.currentTimeMillis() - old.decidedAt < 30 * 86400_000L
            )
                return@synchronized false
            if (
                old?.state == "PENDING" &&
                    sameInput &&
                    old.confidence > proposal.confidence
            )
                return@synchronized false
            if (old?.state == "PENDING") save(old.copy(state = "SUPERSEDED"))
            save(proposal)
            if (updateHints) InferredGenres.update(context, proposal)
            PersonalArchive.atomicWrite(pointer, proposal.id.toByteArray())
            true
        }

    fun decide(id: String, state: String): EnrichmentProposal =
        synchronized(lock) {
            require(state in setOf("ACCEPTED", "REJECTED", "REVERTED"))
            val old = get(id) ?: error("Suggestion no longer exists")
            check(
                old.state == "PENDING" && state in setOf("ACCEPTED", "REJECTED") ||
                    old.state == "ACCEPTED" && state == "REVERTED"
            )
            old.copy(state = state, decidedAt = System.currentTimeMillis()).also {
                save(it)
                InferredGenres.update(context, it)
                LyricTimingFeedback(context).record(it)
            }
        }

    fun adjustTiming(id: String, lines: List<TimedLine>, duration: Long): EnrichmentProposal = synchronized(lock) {
        val old = get(id) ?: error("Suggestion no longer exists")
        require(old.kind == "LYRICS" && old.state == "PENDING")
        require(LocalLyricAlignment.valid(lines, duration))
        require(lines.map { it.text } == LocalLyricAlignment.parse(old.value).map { it.text })
        old.copy(value = LocalLyricAlignment.lrc(lines), predictedValue = old.predictedValue ?: old.value).also(::save)
    }

    fun proposal(
        song: Song,
        kind: String,
        value: String,
        confidence: Double,
        method: String,
        evidence: Int,
        before: LyricsData? = null,
        examples: List<String> = emptyList(),
    ): EnrichmentProposal {
        val signature = signature(song)
        return EnrichmentProposal(
            CatalogFileStore.hash(if (kind == "LYRICS") "$kind|$signature|$value|${gson.toJson(before)}" else "$kind|$signature|$value"),
            kind,
            song.uri.toString(),
            song.title,
            song.artist,
            signature,
            value,
            confidence,
            method,
            evidence,
            song.genre,
            before,
            examples,
        )
    }

    private fun save(proposal: EnrichmentProposal) {
        PersonalArchive.atomicWrite(
            File(proposals, "${proposal.id}.json"),
            gson.toJson(proposal).toByteArray(),
        )
        revision.value++
        PersonalArchive.enqueue(context)
    }

    fun mode(): String =
        context
            .getSharedPreferences("local_enrichment", Context.MODE_PRIVATE)
            .getString("alignment_mode", "LIGHT")
            .orEmpty()
            .takeIf { it in setOf("LIGHT", "PRECISE", "HYBRID") } ?: "LIGHT"

    fun mode(value: String) {
        require(value in setOf("LIGHT", "PRECISE", "HYBRID"))
        context
            .getSharedPreferences("local_enrichment", Context.MODE_PRIVATE)
            .edit()
            .putString("alignment_mode", value)
            .apply()
        EnrichmentLearningWorker.enqueue(context)
    }
}
