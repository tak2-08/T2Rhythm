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
import kotlinx.coroutines.*
import kr.co.metadata.crawler.CatalogFileStore

@Keep data class LyricAlignmentRequest(val signature: String, val lyrics: LyricsData)

/** A user request produces a separate proposal. Applying it remains an explicit review action. */
class LyricSuggestionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(AnalysisBudget.dispatcher) {
        val key = inputData.getString("key") ?: return@withContext Result.failure()
        val file = requestFile(applicationContext, key)
        val budget = AnalysisBudget(applicationContext, { !isStopped }, 90_000)
        try {
            if (!budget.allowed()) return@withContext Result.retry()
            require(file.length() in 1..1_000_000)
            val request = Gson().fromJson(file.readText(), LyricAlignmentRequest::class.java)
            val song = LocalMusicModule.provideMusicRepository(applicationContext).loadSongs()
                .firstOrNull { chromahub.rhythm.app.features.local.data.metadata.SongArtworkOverrides.key(it) == key }
                ?: return@withContext Result.failure(workDataOf("reason" to "stale"))
            check(request.signature == "${song.dateModified}:${song.duration}")
            val store = EnrichmentStore(applicationContext)
            val text = LocalLyricAlignment.text(request.lyrics.plainLyrics, null)
            require(text.size in 3..1000 && request.lyrics.syncedLyrics.isNullOrBlank())
            val ledger = ListeningLedger.get(applicationContext)
            val signature = "${song.dateModified}:${song.duration}"
            val feature = ledger.feature(song.uri.toString(), signature)
                ?: AudioAnalysisWorker.analyze(applicationContext, song).also { ledger.saveFeature(song.uri.toString(), signature, it) }
            budget.checkpoint()
            val precise = if (store.mode() != "LIGHT" && SpeechModelStore.installed(applicationContext)) {
                SpeechAlignment.schedule(applicationContext, song)
                SpeechAlignment.guess(applicationContext, song, text, budget)
            } else null
            val model = store.model()
            val references = TimingReferencePolicy.balanced(LyricTimingFeedback(applicationContext).read().map { it.example } + model.timings)
                .filter { it.key != song.uri.toString() }
            val guess = precise ?: LocalLyricAlignment.light(text, song.duration, feature.pcmSha256, song.genre.orEmpty(), feature.alignmentEnvelope, references)
            if (guess == null) return@withContext Result.failure(workDataOf("reason" to "insufficient"))
            store.propose(store.proposal(song, "LYRICS", LocalLyricAlignment.lrc(guess.lines), guess.confidence, guess.method, guess.evidence, request.lyrics))
            file.delete()
            Result.success()
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: AnalysisDeferred) { Result.retry() }
        catch (_: AnalysisSliceEnded) { Result.retry() }
        catch (_: Exception) { Result.failure(workDataOf("reason" to "failed")) }
    }
    companion object {
        fun name(key: String) = "lyric_suggestion:${CatalogFileStore.hash(key)}"
        private fun requestFile(context: Context, key: String) = File(context.filesDir, "music_metadata/personalization/lyric_requests/${CatalogFileStore.hash(key)}.json")
        suspend fun enqueue(context: Context, song: Song, lyrics: LyricsData) = withContext(Dispatchers.IO) {
            require(lyrics.syncedLyrics.isNullOrBlank() && lyrics.wordByWordLyrics.isNullOrBlank())
            val key = chromahub.rhythm.app.features.local.data.metadata.SongArtworkOverrides.key(song)
            // Store only lyrics and stable identifiers; Android Uri is not a JSON transport type.
            val bytes = Gson().toJson(LyricAlignmentRequest("${song.dateModified}:${song.duration}", lyrics)).toByteArray()
            require(bytes.size <= 1_000_000)
            PersonalArchive.atomicWrite(requestFile(context, key), bytes)
            AudioAnalysisWorker.enqueue(context)
            EnrichmentLearningWorker.enqueue(context)
            WorkManager.getInstance(context).enqueueUniqueWork(name(key), ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LyricSuggestionWorker>().setInputData(workDataOf("key" to key))
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()).build())
        }
    }
}
