// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.content.Context
import androidx.work.*
import chromahub.rhythm.app.features.local.data.experience.*
import chromahub.rhythm.app.features.local.di.LocalMusicModule
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

/** Incremental, charging-only learning. Suggested genres are never treated as ground truth. */
class EnrichmentLearningWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(AnalysisBudget.dispatcher) {
            val job = currentCoroutineContext()[Job]
            val budget = AnalysisBudget(applicationContext, { job?.isActive != false }, 90_000)
            if (!budget.allowed()) return@withContext Result.retry()
            val state =
                applicationContext.getSharedPreferences("enrichment_progress", Context.MODE_PRIVATE)
            try {
                val repository = LocalMusicModule.provideMusicRepository(applicationContext)
                val songs = repository.loadSongs().sortedBy { it.uri.toString() }
                val ledger = ListeningLedger.get(applicationContext)
                val store = EnrichmentStore(applicationContext)
                val databaseMissing =
                    !java.io.File(applicationContext.cacheDir, "enrichment-learning.db").exists()
                val phase = if (databaseMissing) "TRAIN" else state.getString("phase", "TRAIN")
                val begin =
                    if (databaseMissing) 0 else state.getInt("offset", 0).coerceIn(0, songs.size)
                val batch = songs.drop(begin).take(128)
                EnrichmentLearningIndex(applicationContext).use { index ->
                    if (phase == "TRAIN")
                        batch.forEachIndexed { i, song ->
                            budget.checkpoint()
                            val feature =
                                ledger.feature(
                                    song.uri.toString(),
                                    "${song.dateModified}:${song.duration}",
                                )
                            val lyrics = repository.peekLocalLyrics(song)
                            val lines = LocalLyricAlignment.parse(lyrics?.syncedLyrics.orEmpty())
                            val label =
                                song.genre?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf {
                                    it.isNotEmpty() &&
                                        it.lowercase() !in setOf("unknown", "<unknown>", "알 수 없음")
                                }
                            val timing =
                                if (
                                    feature != null &&
                                        lines.size <= 400 &&
                                        TimingReferencePolicy.trusted(lines, song.duration, lyrics?.source)
                                )
                                    TimingTraining(
                                        song.uri.toString(),
                                        feature.pcmSha256,
                                        label.orEmpty(),
                                        song.duration,
                                        lines.map { it.copy(text = it.text.take(400)) },
                                    )
                                else null
                            index.put(
                                song.uri.toString(),
                                label,
                                feature?.let(AudioVectors::describe),
                                timing,
                                EnrichmentStore.signature(song),
                            )
                            check(state.edit().putInt("offset", begin + i + 1).commit())
                        }
                    if (phase == "TRAIN") {
                        if (begin + batch.size >= songs.size)
                            check(
                                state
                                    .edit()
                                    .putString("phase", "INFER")
                                    .putInt("offset", 0)
                                    .commit()
                            )
                        enqueue(applicationContext, true)
                        return@withContext Result.success()
                    }
                    val candidate = index.model(store.list(1000), budget::blockingCheckpoint,
                        LyricTimingFeedback(applicationContext).read().map { it.example })
                    val model = TimingValidation.select(applicationContext, candidate, store.model(), budget::blockingCheckpoint)
                    store.saveModel(model)
                    for ((i, song) in batch.withIndex()) {
                        run inferSong@{
                            budget.checkpoint()
                            val key = song.uri.toString()
                            val feature =
                                ledger.feature(key, "${song.dateModified}:${song.duration}")
                                    ?: return@inferSong
                            if (
                                song.genre.isNullOrBlank() ||
                                    song.genre.lowercase() in
                                        setOf("unknown", "<unknown>", "알 수 없음")
                            ) {
                                LocalGenreClassifier.predict(
                                        key,
                                        AudioVectors.describe(feature),
                                        model.examples,
                                    )
                                    ?.let { guess ->
                                        val proposal =
                                            store.proposal(
                                                song,
                                                "GENRE",
                                                guess.genre,
                                                guess.confidence,
                                                "local_neighbors",
                                                guess.neighbors.size,
                                                examples = guess.neighbors,
                                            )
                                        store.propose(proposal)
                                    }
                            }
                            val lyrics = repository.peekLocalLyrics(song) ?: return@inferSong
                            if (!lyrics.wordByWordLyrics.isNullOrBlank()) return@inferSong
                            val text =
                                LocalLyricAlignment.text(lyrics.plainLyrics, lyrics.syncedLyrics)
                            if (text.isEmpty()) return@inferSong
                            val mode = store.mode()
                            if (mode != "LIGHT") SpeechAlignment.schedule(applicationContext, song)
                            val precise =
                                if (mode != "LIGHT")
                                    SpeechAlignment.guess(applicationContext, song, text, budget)
                                else null
                            val light =
                                if (mode != "PRECISE" || precise == null)
                                    LocalLyricAlignment.light(
                                        text,
                                        song.duration,
                                        feature.pcmSha256,
                                        song.genre.orEmpty(),
                                        feature.alignmentEnvelope.orEmpty(),
                                        model.timings.filter { it.key != key },
                                    )
                                else null
                            val guess =
                                if (
                                    precise != null &&
                                        (light == null || precise.confidence >= light.confidence)
                                )
                                    precise
                                else light
                            if (guess != null) {
                                val lrc = LocalLyricAlignment.lrc(guess.lines)
                                val original =
                                    LocalLyricAlignment.parse(lyrics.syncedLyrics.orEmpty())
                                val changed =
                                    original.map { it.ms } != guess.lines.map { it.ms } ||
                                        original.map { LocalLyricAlignment.normalized(it.text) } !=
                                            guess.lines.map {
                                                LocalLyricAlignment.normalized(it.text)
                                            }
                                if (changed)
                                    store.propose(
                                        store.proposal(
                                            song,
                                            "LYRICS",
                                            lrc,
                                            guess.confidence,
                                            guess.method,
                                            guess.evidence,
                                            lyrics,
                                        )
                                    )
                            }
                        }
                        check(state.edit().putInt("offset", begin + i + 1).commit())
                    }
                }
                val next = begin + batch.size
                if (next < songs.size) enqueue(applicationContext, true)
                else {
                    state
                        .edit()
                        .putString("phase", "TRAIN")
                        .putInt("offset", 0)
                        .putLong("lastPass", System.currentTimeMillis())
                        .apply()
                }
                Result.success()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: AnalysisDeferred) {
                Result.retry()
            } catch (_: AnalysisSliceEnded) {
                enqueue(applicationContext, true)
                Result.success()
            } catch (_: Exception) {
                Result.failure()
            }
        }

    companion object {
        fun enqueue(context: Context, continuation: Boolean = false) {
            val constraints =
                Constraints.Builder()


                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniquePeriodicWork(
                "enrichment_learning_periodic",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<EnrichmentLearningWorker>(12, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build(),
            )
            manager.enqueueUniqueWork(
                "enrichment_learning",
                if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<EnrichmentLearningWorker>()
                    .setConstraints(constraints)
                    .build(),
            )
        }
    }
}
