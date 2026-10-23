// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.content.Context
import android.util.AtomicFile
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
    val artist: String, val genre: String, val updatedAt: Long, val signals: GenreSignals? = null)

/** Durable, explicit labels. Upserting a song replaces its vote; predictions never enter this file. */
class GenreCorrections(private val context: Context) {
    companion object { val revision = MutableStateFlow(0L); private val lock = Any() }
    private val gson = Gson()
    private val file get() = File(context.filesDir, "music_metadata/personalization/learning/genre-corrections.json")
    fun read(): List<GenreCorrection> = synchronized(lock) {
        if (!file.exists() && !File(file.path + ".bak").exists()) return@synchronized emptyList()
        val bytes = AtomicFile(file).openRead().use { stream ->
            require(file.length() in 1..2_000_000)
            stream.readBytes()
        }
        val original = gson.fromJson(bytes.toString(Charsets.UTF_8), Array<GenreCorrection>::class.java).orEmpty().toList()
        val rows = original.map { it.copy(genre = GenreUtils.splitGenres(it.genre).joinToString(", ")) }
            .filter { it.genre.isNotBlank() }.distinctBy { it.songKey }.take(512)
        if (rows != original) write(rows)
        rows
    }
    fun put(song: Song, genre: String) = synchronized(lock) {
        val labels = GenreUtils.splitGenres(genre)
        require(labels.size in 1..8 && labels.all { it.length <= 80 })
        val feature = ListeningLedger.get(context).feature(song.uri.toString(), "${song.dateModified}:${song.duration}")
        val signals = feature?.let { GenreSignalFeatures.extract(it, song.title,
            chromahub.rhythm.app.features.local.di.LocalMusicModule.provideMusicRepository(context).peekLocalLyrics(song)) }
        val value = GenreCorrection(song.uri.toString(), EnrichmentStore.signature(song),
            song.title.take(300), song.artist.take(300), labels.joinToString(", "), System.currentTimeMillis(), signals)
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
        return EnrichmentLearningIndex(context).use { index -> read().flatMap { correction ->
            val song = catalog[correction.songKey]?.takeIf { EnrichmentStore.signature(it) == correction.signature }
                ?: return@flatMap emptyList()
            val feature = ledger.feature(correction.songKey, "${song.dateModified}:${song.duration}")
            val signals = cachedSignals(index, correction) ?: feature?.let { GenreSignalFeatures.extract(it, song.title,
                    chromahub.rhythm.app.features.local.di.LocalMusicModule.provideMusicRepository(context).peekLocalLyrics(song)) } ?: correction.signals
            val vector = feature?.let(AudioVectors::describe) ?: signals?.waveform?.takeIf { GenreSignalFeatures.valid(signals) && it.isNotEmpty() }
                ?: return@flatMap emptyList()
            GenreUtils.splitGenres(correction.genre).map { GenreExample(correction.songKey, it, vector, signals = signals) }
        } }
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
        return rows.map { it.songKey }.toSet() to EnrichmentLearningIndex(context).use { index -> rows.flatMap { correction ->
            val signature = correction.signature.removePrefix("${correction.songKey}:")
            val feature = ledger.feature(correction.songKey, signature)
            val generated = feature?.let { GenreSignalFeatures.extract(it, correction.title, null) }
            val saved = correction.signals?.takeIf(GenreSignalFeatures::valid)
            val signals = cachedSignals(index, correction) ?: if (saved != null && generated != null)
                generated.copy(lyrics = saved.lyrics, fingerprint = generated.fingerprint.ifEmpty { saved.fingerprint }) else generated ?: saved
            val vector = feature?.let(AudioVectors::describe) ?: signals?.waveform?.takeIf { it.isNotEmpty() } ?: return@flatMap emptyList()
            GenreUtils.splitGenres(correction.genre).map { GenreExample(correction.songKey, it, vector, signals = signals) }
        } }
    }
    private fun cachedSignals(index: EnrichmentLearningIndex, row: GenreCorrection): GenreSignals? =
        if (File(context.cacheDir, "enrichment-learning.db").isFile) index.signals(row.songKey, row.signature) else null
    fun refreshModel(songs: List<Song>, changed: Song) {
        val effective = apply(songs)
        val target = effective.firstOrNull { it.uri == changed.uri } ?: changed
        val feature = ListeningLedger.get(context).feature(changed.uri.toString(), "${changed.dateModified}:${changed.duration}")
        val vector = feature?.let(AudioVectors::describe)
        val signals = feature?.let { GenreSignalFeatures.extract(it, changed.title,
            chromahub.rhythm.app.features.local.di.LocalMusicModule.provideMusicRepository(context).peekLocalLyrics(changed)) }
        EnrichmentLearningIndex(context).use { it.updateGenre(changed, target.genre, vector, signals) }
        val store = EnrichmentStore(context)
        val model = store.model()
        val keys = read().map { it.songKey }.toSet() + changed.uri.toString()
        val restored = if (vector != null && changed.uri.toString() !in read().map { it.songKey })
            GenreUtils.splitGenres(changed.genre).map { GenreExample(changed.uri.toString(), it, vector, signals = signals) } else emptyList()
        store.saveModel(model.copy(examples = GenreTrainingPolicy.merge(model.examples.filterNot { it.key in keys } + restored, examples(songs))))
    }
}

object GenreTrainingPolicy {
    fun merge(automatic: List<GenreExample>, reviewed: List<GenreExample>): List<GenreExample> {
        val overrides = reviewed.map { it.key }.toSet()
        val reviewedPcm = reviewed.mapNotNull { it.signals?.pcm?.takeIf { value -> value.length == 64 } }.toSet()
        val counts = mutableMapOf<String, Int>()
        val seen = mutableSetOf<Triple<String, String, Boolean>>()
        return (reviewed + automatic.filterNot { it.key in overrides || it.signals?.pcm in reviewedPcm }).asSequence().flatMap { example ->
            GenreUtils.normalizedGenres(example.genre).asSequence().map { example.copy(genre = it) }
        }.filter { example ->
            example.vector.isNotEmpty() && example.vector.all(Double::isFinite) &&
                seen.add(Triple(example.signals?.pcm?.takeIf { it.length == 64 } ?: example.key, example.genre, example.positive)) &&
                (counts[example.genre] ?: 0) < 64
        }.onEach { counts[it.genre] = (counts[it.genre] ?: 0) + 1 }.take(4096).toList()
    }
}
