// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.content.Context
import androidx.work.*
import chromahub.rhythm.app.features.local.data.experience.*
import chromahub.rhythm.app.features.local.data.metadata.CatalogEnrichmentService
import chromahub.rhythm.app.features.local.di.LocalMusicModule
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/** Learn user labels first, inspect bounded trusted references, validate, then retry rejected requests. */
class TimingLearningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(AnalysisBudget.dispatcher) {
        val user = inputData.getBoolean("user", false)
        val budget = AnalysisBudget(applicationContext, { !isStopped }, 420_000, userInitiated = user)
        if (!budget.allowed()) return@withContext Result.retry()
        try {
            val repository = LocalMusicModule.provideMusicRepository(applicationContext)
            val songs = repository.loadSongs()
            val ledger = ListeningLedger.get(applicationContext)
            val feedback = LyricTimingFeedback(applicationContext)
            val store = EnrichmentStore(applicationContext)
            val catalog = CatalogEnrichmentService.get(applicationContext)
            var decoded = 0
            var speechScheduled = 0
            val resolved = mutableListOf<chromahub.rhythm.app.shared.data.model.Song>()
            suspend fun feature(song: chromahub.rhythm.app.shared.data.model.Song): AudioFeatures? {
                ledger.feature(song.uri.toString(), "${song.dateModified}:${song.duration}")?.let { return it }
                if (decoded >= 4 || catalog.audioBusy(song)) return null
                budget.checkpoint(); decoded++
                catalog.withAudioWrite(song) {
                    ledger.saveFeature(song.uri.toString(), "${song.dateModified}:${song.duration}", AudioAnalysisWorker.analyze(applicationContext, song, userInitiated = user)); true
                }
                return ledger.feature(song.uri.toString(), "${song.dateModified}:${song.duration}")
            }
            for (review in feedback.read()) {
                budget.checkpoint()
                val song = songs.firstOrNull { it.uri.toString() == review.example.key } ?: continue
                if (review.sourceSignature != null && review.sourceSignature != EnrichmentStore.signature(song)) { feedback.discard(review.id); continue }
                if (review.pending && feature(song) != null || !review.calibrationAttempted && SpeechAlignment.read(applicationContext, song.uri.toString())?.complete == true)
                    resolved += song
                if (speechScheduled < 2 && !review.calibrationAttempted && store.mode() != "LIGHT" && SpeechModelStore.installed(applicationContext)) {
                    SpeechAlignment.schedule(applicationContext, song, userInitiated = user); speechScheduled++
                }
            }
            feedback.resolveAll(resolved)
            val candidates = mutableListOf<TimingTraining>()
            // Rotate the bounded scan so a large library does not permanently hide later references.
            val prefs = applicationContext.getSharedPreferences("timing_learning", Context.MODE_PRIVATE)
            val ordered = songs.sortedBy { it.uri.toString() }
            val offset = prefs.getInt("offset", 0).coerceIn(0, ordered.size)
            val batch = (ordered.drop(offset) + ordered.take(offset)).take(128)
            EnrichmentLearningIndex(applicationContext).use { index ->
                for (song in batch) {
                    budget.checkpoint()
                    val lyrics = repository.peekLocalLyrics(song) ?: continue
                    val lines = LocalLyricAlignment.parse(lyrics.syncedLyrics.orEmpty())
                    if (!TimingReferencePolicy.trusted(lines, song.duration, lyrics.source)) continue
                    val audio = feature(song) ?: continue
                    val timing = TimingTraining(song.uri.toString(), audio.pcmSha256, song.genre.orEmpty(), song.duration,
                        lines.map { it.copy(text = it.text.take(400)) }, audio = AudioVectors.describe(audio))
                    candidates += timing
                    index.put(song.uri.toString(), song.genre, AudioVectors.describe(audio), timing, EnrichmentStore.signature(song))
                }
                prefs.edit().putInt("offset", if (ordered.isEmpty()) 0 else (offset + batch.size) % ordered.size).apply()
                val old = store.model()
                val references = feedback.examples() + candidates + index.model(emptyList(), budget::blockingCheckpoint).timings
                val model = TimingValidation.select(applicationContext, old.copy(timings = references), old, budget::blockingCheckpoint)
                store.saveModel(model)
                TimingRetryQueue(applicationContext).schedule(model)
            }
            setProgress(workDataOf("stage" to "complete", "examples" to feedback.examples().size))
            val pass = inputData.getInt("pass", 0)
            if (pass < 2 && decoded >= 4 && feedback.read().any { it.pending && songs.any { song -> song.uri.toString() == it.example.key } })
                enqueue(applicationContext, pass = pass + 1)
            Result.success()
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: AnalysisDeferred) { Result.retry() }
        catch (_: AnalysisSliceEnded) { Result.retry() }
        catch (_: Exception) { TimingRetryQueue(applicationContext).learningFailed(); Result.failure() }
    }
    companion object {
        const val NAME = "lyric_timing_feedback_learning"
        fun enqueue(context: Context, userInitiated: Boolean = false, pass: Int = 0) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME,
                if (userInitiated) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<TimingLearningWorker>().setInputData(workDataOf("user" to userInitiated, "pass" to pass))
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()).build())
        }
    }
}
