// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.content.Context
import androidx.annotation.Keep
import chromahub.rhythm.app.features.local.data.metadata.PersonalArchive
import com.google.gson.Gson
import java.io.File

@Keep data class GenreValidationResult(val recordings: Int, val accuracy: Double?, val previousAccuracy: Double?,
    val promoted: Boolean, val provisional: Boolean, val at: Long = System.currentTimeMillis())

/** Recording-level cross validation; identical decoded PCM cannot be both a teacher and its own exam. */
object GenreValidation {
    data class Evaluation(val accuracy: Double, val wrong: Int)
    private fun identity(example: GenreExample) = example.signals?.pcm?.takeIf { it.length == 64 } ?: example.key
    fun evaluate(model: List<GenreExample>, exam: List<GenreExample>, checkpoint: () -> Unit = {}): Evaluation {
        val results = exam.groupBy(::identity).map { (recording, rows) ->
            checkpoint()
            val sample = rows.first(); val labels = rows.map { it.genre }.toSet()
            val guess = LocalGenreClassifier.predict(sample.key, sample.signals!!,
                model.filterNot { identity(it) == recording }, excludePcm = true)
            Triple(labels, guess?.genre in labels, guess != null && guess.genre !in labels)
        }
        val classes = results.flatMap { it.first }.distinct()
        val balanced = classes.map { genre -> results.filter { genre in it.first }.let { group -> group.count { it.second }.toDouble() / group.size } }.average()
        return Evaluation(balanced.takeIf(Double::isFinite) ?: 0.0, results.count { it.third })
    }
    fun select(context: Context, candidate: EnrichmentModel, previous: EnrichmentModel, checkpoint: () -> Unit = {}): EnrichmentModel {
        val positives = candidate.examples.filter { it.positive && GenreSignalFeatures.valid(it.signals) }
        val groups = positives.groupBy { it.genre }
        val exam = groups.toSortedMap().values.flatMap { rows -> rows.distinctBy(::identity)
            .sortedBy { kr.co.metadata.crawler.CatalogFileStore.hash(identity(it)) }.take(4) }
            .distinctBy { it.key to it.genre }.take(24)
        val count = exam.map(::identity).distinct().size
        val provisional = positives.map(::identity).distinct().size < 16 || groups.size < 2 || count < 6
        var next: Evaluation? = null; var old: Evaluation? = null
        if (!provisional) {
            next = evaluate(candidate.examples, exam, checkpoint)
            old = evaluate(previous.examples, exam, checkpoint)
        }
        val promoted = provisional || old == null || next!!.accuracy + .03 >= old.accuracy && next.wrong <= old.wrong + 1
        val result = GenreValidationResult(count, next?.accuracy, old?.accuracy, promoted, provisional)
        PersonalArchive.atomicWrite(File(context.cacheDir, "genre-validation.json"), Gson().toJson(result).toByteArray())
        return if (promoted) candidate else candidate.copy(examples = previous.examples)
    }
    fun read(context: Context): GenreValidationResult? = runCatching {
        val file = File(context.cacheDir, "genre-validation.json"); require(file.length() in 1..8000)
        Gson().fromJson(file.readText(), GenreValidationResult::class.java)
    }.getOrNull()
}
