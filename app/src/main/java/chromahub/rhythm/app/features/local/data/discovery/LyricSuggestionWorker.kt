// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.content.Context
import androidx.annotation.Keep
import androidx.work.*
import chromahub.rhythm.app.features.local.data.experience.*
import chromahub.rhythm.app.features.local.data.metadata.PersonalArchive
import chromahub.rhythm.app.features.local.di.LocalMusicModule
import chromahub.rhythm.app.shared.data.model.LyricsData
import chromahub.rhythm.app.shared.data.model.Song
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kr.co.metadata.crawler.CatalogFileStore

@Keep data class LyricAlignmentRequest(val signature: String, val lyrics: LyricsData)

/** A user request produces a separate proposal. Applying it remains an explicit review action. */
class LyricSuggestionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(AnalysisBudget.dispatcher) {
        val key = inputData.getString("key") ?: return@withContext Result.failure()
        val file = requestFile(applicationContext, key)
        val budget = AnalysisBudget(applicationContext, { !isStopped }, 480_000, userInitiated = true)
        try {
            if (!budget.allowed()) {
                setProgress(workDataOf("stage" to "waiting"))
                return@withContext Result.retry()
            }
            require(file.length() in 1..1_000_000)
            val request = Gson().fromJson(file.readText(), LyricAlignmentRequest::class.java)
            val song = LocalMusicModule.provideMusicRepository(applicationContext).loadSongs()
                .firstOrNull { chromahub.rhythm.app.features.local.data.metadata.SongArtworkOverrides.key(it) == key }
                ?: return@withContext Result.failure(workDataOf("reason" to "stale"))
            check(request.signature == "${song.dateModified}:${song.duration}")
            val store = EnrichmentStore(applicationContext)
            if (store.mode() == "PRECISE" && !SpeechModelStore.installed(applicationContext))
                return@withContext Result.failure(workDataOf("reason" to "model_missing"))
            val text = LyricTimingInput.lines(request.lyrics)
            require(text.size in 3..1000)
            val ledger = ListeningLedger.get(applicationContext)
            val signature = "${song.dateModified}:${song.duration}"
            setProgress(workDataOf("stage" to "audio"))
            val feature = ledger.feature(song.uri.toString(), signature)
                ?: AudioAnalysisWorker.analyze(applicationContext, song, userInitiated = true).also { ledger.saveFeature(song.uri.toString(), signature, it) }
            budget.checkpoint()
            val useSpeech = store.mode() != "LIGHT" && SpeechModelStore.installed(applicationContext)
            val precise = if (useSpeech) {
                val speech = SpeechAlignment.read(applicationContext, song.uri.toString())
                if (speech?.signature != EnrichmentStore.signature(song) || !speech.complete) {
                    if ((speech?.failures ?: 0) >= 3) return@withContext Result.failure(workDataOf("reason" to "speech_failed"))
                    SpeechAlignment.schedule(applicationContext, song, userInitiated = true)
                    setProgress(workDataOf("stage" to "speech", "percent" to ((speech?.offsetMs ?: 0) * 100 / song.duration.coerceAtLeast(1)).toInt()))
                    return@withContext Result.retry()
                }
                SpeechAlignment.guess(applicationContext, song, text, budget)
            } else null
            setProgress(workDataOf("stage" to "matching"))
            val model = store.model()
            val references = TimingReferencePolicy.balanced(LyricTimingFeedback(applicationContext).read().map { it.example } + model.timings)
                .filter { it.key != song.uri.toString() }
            val guess = precise ?: if (store.mode() == "PRECISE") null else LocalLyricAlignment.light(text, song.duration, feature.pcmSha256, song.genre.orEmpty(), feature.alignmentEnvelope, references)
            if (guess == null) return@withContext Result.failure(workDataOf("reason" to "insufficient"))
            store.propose(store.proposal(song, "LYRICS", LocalLyricAlignment.lrc(guess.lines), guess.confidence, guess.method, guess.evidence, request.lyrics))
            file.delete()
            EnrichmentLearningWorker.enqueue(applicationContext)
            Result.success()
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: AnalysisDeferred) { setProgress(workDataOf("stage" to "waiting")); Result.retry() }
        catch (_: AnalysisSliceEnded) { Result.failure(workDataOf("reason" to "timeout")) }
        catch (_: Exception) { Result.failure(workDataOf("reason" to "failed")) }
    }
    companion object {
        fun name(key: String) = "lyric_suggestion:${CatalogFileStore.hash(key)}"
        private fun requestFile(context: Context, key: String) = File(context.filesDir, "music_metadata/personalization/lyric_requests/${CatalogFileStore.hash(key)}.json")
        suspend fun cancel(context: Context, song: Song) = withContext(Dispatchers.IO) {
            val key = chromahub.rhythm.app.features.local.data.metadata.SongArtworkOverrides.key(song)
            WorkManager.getInstance(context).cancelUniqueWork(name(key))
            WorkManager.getInstance(context).cancelUniqueWork("speech_alignment:${CatalogFileStore.hash(song.uri.toString())}")
            requestFile(context, key).delete()
        }
        suspend fun enqueue(context: Context, song: Song, lyrics: LyricsData) = withContext(Dispatchers.IO) {
            require(LyricTimingInput.lines(lyrics).size in 3..1000)
            val key = chromahub.rhythm.app.features.local.data.metadata.SongArtworkOverrides.key(song)
            // Store only lyrics and stable identifiers; Android Uri is not a JSON transport type.
            val bytes = Gson().toJson(LyricAlignmentRequest("${song.dateModified}:${song.duration}", lyrics)).toByteArray()
            require(bytes.size <= 1_000_000)
            PersonalArchive.atomicWrite(requestFile(context, key), bytes)
            if (EnrichmentStore(context).mode() != "LIGHT" && SpeechModelStore.installed(context))
                SpeechAlignment.schedule(context, song, userInitiated = true, replaceBackground = true)
            WorkManager.getInstance(context).enqueueUniqueWork(name(key), ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<LyricSuggestionWorker>().setInputData(workDataOf("key" to key))
                    .addTag("lyric_request_${System.currentTimeMillis()}")
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()).build())
        }
    }
}
