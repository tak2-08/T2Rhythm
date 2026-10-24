// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.app.ActivityManager
import android.content.Context
import androidx.annotation.Keep
import androidx.work.*
import chromahub.rhythm.app.features.local.data.experience.*
import chromahub.rhythm.app.features.local.data.metadata.*
import chromahub.rhythm.app.shared.data.model.Song
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kr.co.metadata.crawler.CatalogFileStore
import org.vosk.LibVosk
import org.vosk.Model
import org.vosk.Recognizer

@Keep
data class SpeechCheckpoint(
    val signature: String,
    val song: String,
    val offsetMs: Long = 0,
    val chunkMs: Long = 6000,
    val words: List<SpeechWord> = emptyList(),
    val complete: Boolean = false,
    val failures: Int = 0,
)

object SpeechAlignment {
    private val gson = Gson()

    private fun file(context: Context, key: String) =
        File(context.filesDir, "music_metadata/speech/${CatalogFileStore.hash(key)}.json")

    fun read(context: Context, key: String): SpeechCheckpoint? =
        runCatching {
                val f = file(context, key)
                require(f.length() in 1..3_000_000)
                gson.fromJson(f.readText(), SpeechCheckpoint::class.java)
            }
            .getOrNull()

    internal fun save(context: Context, key: String, checkpoint: SpeechCheckpoint) {
        PersonalArchive.atomicWrite(file(context, key), gson.toJson(checkpoint).toByteArray())
    }

    internal fun guess(
        context: Context,
        song: Song,
        lines: List<String>,
        budget: AnalysisBudget,
    ): TimingGuess? {
        val checkpoint =
            read(context, song.uri.toString())?.takeIf {
                it.signature == EnrichmentStore.signature(song) && it.complete
            } ?: return null
        return LocalLyricAlignment.speech(
            lines,
            checkpoint.words,
            song.duration,
            budget::blockingCheckpoint,
        )?.let { TimingSpeechCalibration.apply(it, EnrichmentStore(context).model().timings, song.duration) }
    }

    suspend fun schedule(context: Context, song: Song, userInitiated: Boolean = false, replaceBackground: Boolean = false) =
        withContext(Dispatchers.IO) {
            if (song.duration !in 15_000..1_200_000 || !SpeechModelStore.installed(context))
                return@withContext
            val key = song.uri.toString()
            val previous = read(context, key)
            if (
                previous?.signature == EnrichmentStore.signature(song) &&
                    (previous.complete || previous.failures >= 3 && !replaceBackground)
            )
                return@withContext
            if (previous?.signature != EnrichmentStore.signature(song) || replaceBackground && (previous.failures >= 3))
                save(
                    context,
                    key,
                    SpeechCheckpoint(EnrichmentStore.signature(song), CatalogSongCodec.encode(song)),
                )
            enqueue(context, key, false, userInitiated, replaceBackground)
        }

    internal fun enqueue(context: Context, key: String, continuation: Boolean, userInitiated: Boolean = false, replaceBackground: Boolean = false) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "speech_alignment:${CatalogFileStore.hash(key)}",
                if (replaceBackground) ExistingWorkPolicy.REPLACE else if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SpeechAlignmentWorker>()
                    .addTag("speech_alignment")
                    .setInputData(workDataOf("key" to key, "userInitiated" to userInitiated))
                    .setConstraints(
                        Constraints.Builder()


                            .setRequiresBatteryNotLow(true)
                            .setRequiresStorageNotLow(true)
                            .build()
                    )
                    .build(),
            )
    }

    fun merge(before: List<SpeechWord>, next: List<SpeechWord>): List<SpeechWord> {
        val result = before.toMutableList()
        next.forEach { word ->
            val overlapping =
                result.indexOfLast { old ->
                    LocalLyricAlignment.normalized(old.text) ==
                        LocalLyricAlignment.normalized(word.text) &&
                        minOf(old.endMs, word.endMs) - maxOf(old.startMs, word.startMs) >
                            minOf(old.endMs - old.startMs, word.endMs - word.startMs) / 2
                }
            if (overlapping < 0) result += word
            else if (result[overlapping].confidence < word.confidence) result[overlapping] = word
        }
        return result.sortedBy { it.startMs }.take(20_000)
    }
}

class SpeechAlignmentWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(AnalysisBudget.dispatcher) {
            val key = inputData.getString("key") ?: return@withContext Result.failure()
            val store = EnrichmentStore(applicationContext)
            if (store.mode() == "LIGHT" || !SpeechModelStore.installed(applicationContext))
                return@withContext Result.success()
            val saved =
                SpeechAlignment.read(applicationContext, key) ?: return@withContext Result.failure()
            if (saved.complete) return@withContext Result.success()
            val job = currentCoroutineContext()[Job]
            val userInitiated = inputData.getBoolean("userInitiated", false)
            val budget = AnalysisBudget(applicationContext, { job?.isActive != false }, 90_000, userInitiated = userInitiated)
            if (!budget.allowed()) return@withContext Result.retry()
            val memory = ActivityManager.MemoryInfo()
            (applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .getMemoryInfo(memory)
            if (memory.lowMemory || memory.availMem < 384L * 1024 * 1024)
                return@withContext Result.retry()
            try {
                val song = CatalogSongCodec.decode(saved.song)
                val from = (saved.offsetMs - 1000).coerceAtLeast(0)
                val until = (saved.offsetMs + saved.chunkMs).coerceAtMost(song.duration)
                val words = mutableListOf<SpeechWord>()
                fun result(json: String) {
                    val root = JsonParser.parseString(json).asJsonObject
                    root.getAsJsonArray("result")?.take(500)?.forEach { element ->
                        val w = element.asJsonObject
                        val start = (w.get("start").asDouble * 1000).toLong() + from
                        val end = (w.get("end").asDouble * 1000).toLong() + from
                        val conf = w.get("conf").asDouble
                        if (start >= 0 && end in start..song.duration && conf.isFinite())
                            words +=
                                SpeechWord(
                                    start,
                                    end,
                                    w.get("word").asString.take(100),
                                    conf.coerceIn(0.0, 1.0),
                                )
                    }
                }
                SpeechModelStore.access.withLock {
                    budget.checkpoint()
                    LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS)
                    Model(SpeechModelStore.directory(applicationContext).path).use { model ->
                        Recognizer(model, 16000f).use { recognizer ->
                            recognizer.setWords(true)
                            val resampler = SpeechResampler { pcm, n ->
                                budget.blockingCheckpoint()
                                if (recognizer.acceptWaveForm(pcm, n)) result(recognizer.result)
                            }
                            SpeechChunkDecoder.decode(
                                applicationContext,
                                song,
                                from,
                                until,
                                budget,
                                resampler,
                            )
                            resampler.flush()
                            result(recognizer.finalResult)
                        }
                    }
                }
                val next =
                    saved.copy(
                        offsetMs = until,
                        words = SpeechAlignment.merge(saved.words, words),
                        complete = until >= song.duration,
                        failures = 0,
                    )
                SpeechAlignment.save(applicationContext, key, next)
                if (!next.complete) SpeechAlignment.enqueue(applicationContext, key, true, userInitiated)
                else {
                    // Recognition checkpoints are committed before alignment; a time slice never
                    // rolls them back.
                    EnrichmentLearningWorker.enqueue(applicationContext)
                    if (LyricTimingFeedback(applicationContext).read().any { it.example.key == key && !it.calibrationAttempted })
                        TimingLearningWorker.enqueue(applicationContext, userInitiated)
                    PersonalArchive.enqueue(applicationContext)
                }
                Result.success()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: AnalysisDeferred) {
                Result.retry()
            } catch (_: AnalysisSliceEnded) {
                SpeechAlignment.save(
                    applicationContext,
                    key,
                    saved.copy(
                        chunkMs = (saved.chunkMs / 2).coerceAtLeast(1000),
                        failures = if (saved.chunkMs <= 1000) saved.failures + 1 else saved.failures,
                    ),
                )
                if (saved.failures < 3) SpeechAlignment.enqueue(applicationContext, key, true, userInitiated)
                Result.success()
            } catch (_: Exception) {
                SpeechAlignment.save(
                    applicationContext,
                    key,
                    saved.copy(failures = saved.failures + 1),
                )
                if (saved.failures < 2) SpeechAlignment.enqueue(applicationContext, key, true, userInitiated)
                Result.success()
            }
        }
}
