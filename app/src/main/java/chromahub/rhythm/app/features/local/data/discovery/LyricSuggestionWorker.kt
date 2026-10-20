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
        val retryId = inputData.getString("rejectedId")
        fun failure(reason: String): Result {
            retryId?.let { if (reason == "stale") TimingRetryQueue(applicationContext).remove(key)
                else TimingRetryQueue(applicationContext).complete(it, false,
                if (reason == "model_missing") "model_missing" else "needs_examples") }
            file.delete()
            return Result.failure(workDataOf("reason" to reason))
        }
        val budget = AnalysisBudget(applicationContext, { !isStopped }, 480_000, userInitiated = true)
        try {
            if (!budget.allowed()) {
                setProgress(workDataOf("stage" to "waiting"))
                return@withContext Result.retry()
            }
            require(file.length() in 1..1_000_000)
            val request = Gson().fromJson(file.readText(), LyricAlignmentRequest::class.java)
            val song = LocalMusicModule.provideMusicRepository(applicationContext).loadSongs()
                .firstOrNull { (if (inputData.getBoolean("byUri", false)) it.uri.toString() else chromahub.rhythm.app.features.local.data.metadata.SongArtworkOverrides.key(it)) == key }
                ?: return@withContext failure("stale")
            if (request.signature != "${song.dateModified}:${song.duration}") return@withContext failure("stale")
            val store = EnrichmentStore(applicationContext)
            val text = LyricTimingInput.lines(request.lyrics)
            require(text.size in 3..1000)
            val ledger = ListeningLedger.get(applicationContext)
            val signature = "${song.dateModified}:${song.duration}"
            setProgress(workDataOf("stage" to "audio"))
            val feature = ledger.feature(song.uri.toString(), signature)
                ?: AudioAnalysisWorker.analyze(applicationContext, song, userInitiated = true).also { ledger.saveFeature(song.uri.toString(), signature, it) }
            val exact = LyricTimingFeedback(applicationContext).examples().firstOrNull { it.pcm == feature.pcmSha256 &&
                it.lines.map { line -> LocalLyricAlignment.normalized(line.text) } == text.map(LocalLyricAlignment::normalized) }
            if (exact == null && store.mode() == "PRECISE" && !SpeechModelStore.installed(applicationContext))
                return@withContext failure("model_missing")
            budget.checkpoint()
            val useSpeech = exact == null && store.mode() != "LIGHT" && SpeechModelStore.installed(applicationContext)
            val precise = if (useSpeech) {
                val speech = SpeechAlignment.read(applicationContext, song.uri.toString())
                if (speech?.signature != EnrichmentStore.signature(song) || !speech.complete) {
                    if ((speech?.failures ?: 0) >= 3) return@withContext failure("speech_failed")
                    SpeechAlignment.schedule(applicationContext, song, userInitiated = true)
                    setProgress(workDataOf("stage" to "speech", "percent" to ((speech?.offsetMs ?: 0) * 100 / song.duration.coerceAtLeast(1)).toInt()))
                    return@withContext Result.retry()
                }
                SpeechAlignment.guess(applicationContext, song, text, budget)
            } else null
            setProgress(workDataOf("stage" to "matching"))
            val model = store.model()
            val references = TimingReferencePolicy.balanced(model.timings)
            val guess = exact?.let { TimingGuess(it.lines.mapIndexed { i, line -> line.copy(text = text[i]) }, .98, "same_audio_reference", 1) }
                ?: precise ?: if (store.mode() == "PRECISE") null else LocalLyricAlignment.light(text, song.duration, feature.pcmSha256, song.genre.orEmpty(), feature.alignmentEnvelope, references, AudioVectors.describe(feature))
            if (guess == null) return@withContext failure("insufficient")
            // An automatic retry is valid only while the listener still has the rejected input.
            if (retryId != null) {
                val current = LocalMusicModule.provideMusicRepository(applicationContext).peekLocalLyrics(song)
                if (current != null && !EnrichmentStore.sameLyrics(current, request.lyrics)) {
                    TimingRetryQueue(applicationContext).remove(song.uri.toString()); file.delete(); return@withContext Result.success()
                }
            }
            val offered = store.propose(store.proposal(song, "LYRICS", LocalLyricAlignment.lrc(guess.lines), guess.confidence, guess.method,
                guess.evidence, request.lyrics, learningRevision = model.timingRevision ?: TimingReferencePolicy.revision(model.timings)))
            retryId?.let { TimingRetryQueue(applicationContext).complete(it, offered) }
            file.delete()
            EnrichmentLearningWorker.enqueue(applicationContext)
            Result.success()
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: AnalysisDeferred) { setProgress(workDataOf("stage" to "waiting")); Result.retry() }
        catch (_: AnalysisSliceEnded) { failure("timeout") }
        catch (_: Exception) { failure("failed") }
    }
    companion object {
        fun name(key: String) = "lyric_suggestion:${CatalogFileStore.hash(key)}"
        fun enqueueRetry(context: Context, rejected: EnrichmentProposal) {
            val lyrics = rejected.beforeLyrics ?: return
            val key = rejected.songKey
            val signature = rejected.sourceSignature.removePrefix(key + ":")
            PersonalArchive.atomicWrite(requestFile(context, key), Gson().toJson(LyricAlignmentRequest(signature, lyrics)).toByteArray())
            WorkManager.getInstance(context).enqueueUniqueWork(name(key), ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LyricSuggestionWorker>().setInputData(workDataOf("key" to key, "byUri" to true, "rejectedId" to rejected.id))
                    .addTag("lyric_request_${System.currentTimeMillis()}")
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()).build())
        }
        private fun requestFile(context: Context, key: String) = File(context.filesDir, "music_metadata/personalization/lyric_requests/${CatalogFileStore.hash(key)}.json")
        suspend fun cancel(context: Context, song: Song) = withContext(Dispatchers.IO) {
            val key = chromahub.rhythm.app.features.local.data.metadata.SongArtworkOverrides.key(song)
            WorkManager.getInstance(context).cancelUniqueWork(name(key))
            WorkManager.getInstance(context).cancelUniqueWork(name(song.uri.toString()))
            WorkManager.getInstance(context).cancelUniqueWork("speech_alignment:${CatalogFileStore.hash(song.uri.toString())}")
            requestFile(context, key).delete()
            requestFile(context, song.uri.toString()).delete()
            TimingRetryQueue(context).pause(song.uri.toString())
        }
        suspend fun enqueue(context: Context, song: Song, lyrics: LyricsData) = withContext(Dispatchers.IO) {
            TimingRetryQueue(context).resume(song.uri.toString())
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
