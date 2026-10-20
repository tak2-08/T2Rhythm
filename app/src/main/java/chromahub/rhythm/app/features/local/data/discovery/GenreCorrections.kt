// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.content.Context
import androidx.annotation.Keep
import chromahub.rhythm.app.features.local.data.experience.AudioVectors
import chromahub.rhythm.app.features.local.data.experience.ListeningLedger
import chromahub.rhythm.app.features.local.data.metadata.PersonalArchive
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.util.GenreUtils
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

@Keep data class GenreCorrection(val songKey: String, val signature: String, val title: String,
    val artist: String, val genre: String, val updatedAt: Long)

/** Durable, explicit labels. Upserting a song replaces its vote; predictions never enter this file. */
class GenreCorrections(private val context: Context) {
    companion object { val revision = MutableStateFlow(0L); private val lock = Any() }
    private val gson = Gson()
    private val file get() = File(context.filesDir, "music_metadata/personalization/learning/genre-corrections.json")
    fun read(): List<GenreCorrection> = synchronized(lock) {
        if (!file.exists()) return@synchronized emptyList()
        require(file.length() in 1..2_000_000)
        gson.fromJson(file.readText(), Array<GenreCorrection>::class.java).orEmpty()
            .filter { GenreUtils.isKnownGenre(it.genre) }.distinctBy { it.songKey }.take(512)
    }
    fun put(song: Song, genre: String) = synchronized(lock) {
        val labels = GenreUtils.splitGenres(genre)
        require(labels.size in 1..8 && labels.all { it.length <= 80 })
        val value = GenreCorrection(song.uri.toString(), EnrichmentStore.signature(song),
            song.title.take(300), song.artist.take(300), labels.joinToString(", "), System.currentTimeMillis())
        write(listOf(value) + read().filterNot { it.songKey == value.songKey })
    }
    fun remove(key: String) = synchronized(lock) { write(read().filterNot { it.songKey == key }) }
    private fun write(values: List<GenreCorrection>) {
        var bounded = values.take(512)
        var bytes = gson.toJson(bounded).toByteArray()
        // Match the reader limit even for unusually long UTF-8 titles or genre labels.
        while (bytes.size > 2_000_000 && bounded.size > 1) {
            bounded = bounded.take((bounded.size * 3 / 4).coerceAtLeast(1))
            bytes = gson.toJson(bounded).toByteArray()
        }
        require(bytes.size <= 2_000_000)
        PersonalArchive.atomicWrite(file, bytes)
        revision.value++
        ListeningLedger.get(context).invalidate()
        PersonalArchive.enqueue(context)
    }
    fun examples(songs: List<Song>): List<GenreExample> {
        val catalog = songs.associateBy { it.uri.toString() }
        val ledger = ListeningLedger.get(context)
        return read().flatMap { correction ->
            val song = catalog[correction.songKey]?.takeIf { EnrichmentStore.signature(it) == correction.signature }
                ?: return@flatMap emptyList()
            val feature = ledger.feature(correction.songKey, "${song.dateModified}:${song.duration}")
                ?: return@flatMap emptyList()
            GenreUtils.splitGenres(correction.genre).map { GenreExample(correction.songKey, it, AudioVectors.describe(feature)) }
        }
    }
    fun apply(songs: List<Song>): List<Song> {
        val labels = read().associateBy { it.songKey }
        return songs.map { song -> labels[song.uri.toString()]?.takeIf { it.signature == EnrichmentStore.signature(song) }
            ?.let { song.copy(genre = it.genre) } ?: song }
    }
    /** Reapply explicit labels when a worker finishes with an older model snapshot. */
    fun modelExamples(): Pair<Set<String>, List<GenreExample>> {
        val rows = read()
        val ledger = ListeningLedger.get(context)
        return rows.map { it.songKey }.toSet() to rows.flatMap { correction ->
            val signature = correction.signature.removePrefix("${correction.songKey}:")
            val feature = ledger.feature(correction.songKey, signature) ?: return@flatMap emptyList()
            GenreUtils.splitGenres(correction.genre).map { GenreExample(correction.songKey, it, AudioVectors.describe(feature)) }
        }
    }
    fun refreshModel(songs: List<Song>, changed: Song) {
        val effective = apply(songs)
        val target = effective.firstOrNull { it.uri == changed.uri } ?: changed
        val vector = ListeningLedger.get(context).feature(changed.uri.toString(), "${changed.dateModified}:${changed.duration}")?.let(AudioVectors::describe)
        EnrichmentLearningIndex(context).use { it.updateGenre(changed, target.genre, vector) }
        val store = EnrichmentStore(context)
        val model = store.model()
        val keys = read().map { it.songKey }.toSet() + changed.uri.toString()
        val restored = if (vector != null && changed.uri.toString() !in read().map { it.songKey })
            GenreUtils.splitGenres(changed.genre).map { GenreExample(changed.uri.toString(), it, vector) } else emptyList()
        store.saveModel(model.copy(examples = GenreTrainingPolicy.merge(model.examples.filterNot { it.key in keys } + restored, examples(songs))))
    }
}

object GenreTrainingPolicy {
    fun merge(automatic: List<GenreExample>, reviewed: List<GenreExample>): List<GenreExample> {
        val overrides = reviewed.map { it.key }.toSet()
        val counts = mutableMapOf<String, Int>()
        val seen = mutableSetOf<Triple<String, String, Boolean>>()
        return (reviewed + automatic.filterNot { it.key in overrides }).asSequence().flatMap { example ->
            GenreUtils.normalizedGenres(example.genre).asSequence().map { example.copy(genre = it) }
        }.filter { example ->
            example.vector.isNotEmpty() && example.vector.all(Double::isFinite) &&
                seen.add(Triple(example.key, example.genre, example.positive)) &&
                (counts[example.genre] ?: 0) < 64
        }.onEach { counts[it.genre] = (counts[it.genre] ?: 0) + 1 }.take(4096).toList()
    }
}
