// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import android.content.Context
import androidx.work.*
import chromahub.rhythm.app.features.local.data.discovery.*
import chromahub.rhythm.app.features.local.data.metadata.CatalogSongCodec
import chromahub.rhythm.app.features.local.data.metadata.PersonalArchiveWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

/**
 * Migrate existing waits in place: changing future request constraints alone leaves old jobs stuck.
 */
object AnalysisScheduling {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun install(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                migrate(app)
                if (
                    app.getSharedPreferences("analysis_migration", Context.MODE_PRIVATE)
                        .getInt("version", 0) < 1
                )
                    WorkManager.getInstance(app)
                        .enqueueUniqueWork(
                            "analysis_constraint_migration",
                            ExistingWorkPolicy.KEEP,
                            OneTimeWorkRequestBuilder<AnalysisMigrationWorker>()
                                .setConstraints(
                                    Constraints.Builder()
                                        .setRequiresBatteryNotLow(true)
                                        .setRequiresStorageNotLow(true)
                                        .build()
                                )
                                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                                .build(),
                        )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                android.util.Log.w("AnalysisScheduling", "Unable to update pending analysis", error)
            }
        }
    }

    private suspend fun migrate(context: Context) {
        relax(context, "audio_feature_analysis", AudioAnalysisWorker::class.java)
        relax(context, "recommendation_training_v4", RecommendationTrainingWorker::class.java)
        relax(context, "enrichment_learning", EnrichmentLearningWorker::class.java)
        relax(
            context,
            "personal_archive_sync_v2",
            PersonalArchiveWorker::class.java,
            workDataOf("archive_constraints_v2" to true),
        )
    }

    internal fun migrateSpeech(context: Context, budget: AnalysisBudget) {
        val preferences = context.getSharedPreferences("analysis_migration", Context.MODE_PRIVATE)
        val cursor = preferences.getString("speech_cursor", "").orEmpty()
        // Read only small checkpoint headers; skip potentially large recognized-word arrays.
        val root = java.io.File(context.filesDir, "music_metadata/speech")
        for (file in root.listFiles().orEmpty().sortedBy { it.name }) {
            if (file.name <= cursor) continue
            budget.blockingCheckpoint()
            if (file.extension != "json" || file.length() > 3_000_000) continue
            var song: String? = null
            var complete = false
            runCatching {
                com.google.gson.stream.JsonReader(file.reader()).use { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) when (reader.nextName()) {
                        "song" -> song = reader.nextString()
                        "complete" -> complete = reader.nextBoolean()
                        else -> reader.skipValue()
                    }
                }
            }
            if (!complete && song != null) {
                val key =
                    runCatching { CatalogSongCodec.decode(requireNotNull(song)).uri.toString() }
                        .getOrNull() ?: continue
                relax(
                    context,
                    "speech_alignment:${kr.co.metadata.crawler.CatalogFileStore.hash(key)}",
                    SpeechAlignmentWorker::class.java,
                    workDataOf("key" to key),
                )
            }
            check(preferences.edit().putString("speech_cursor", file.name).commit())
        }
    }

    internal fun relax(
        context: Context,
        name: String,
        worker: Class<out ListenableWorker>,
        input: Data = Data.EMPTY,
    ) {
        val manager = WorkManager.getInstance(context)
        manager
            .getWorkInfosForUniqueWork(name)
            .get(15, TimeUnit.SECONDS)
            .filter {
                !it.state.isFinished &&
                    (it.constraints.requiresCharging() || it.constraints.requiresDeviceIdle())
            }
            .forEach { existing ->
                val request =
                    OneTimeWorkRequest.Builder(worker)
                        .setId(existing.id)
                        .setInputData(input)
                        .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiresBatteryNotLow(true)
                                .setRequiresStorageNotLow(true)
                                .build()
                        )
                        .apply {
                            if (worker == SpeechAlignmentWorker::class.java)
                                addTag("speech_alignment")
                        }
                        .build()
                manager.updateWork(request).get(15, TimeUnit.SECONDS)
            }
    }
}

class AnalysisMigrationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(AnalysisBudget.dispatcher) {
            try {
                val budget = AnalysisBudget(applicationContext, { !isStopped }, 15_000)
                budget.checkpoint()
                AnalysisScheduling.migrateSpeech(applicationContext, budget)
                check(
                    applicationContext
                        .getSharedPreferences("analysis_migration", Context.MODE_PRIVATE)
                        .edit()
                        .putInt("version", 1)
                        .commit()
                )
                Result.success()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: AnalysisDeferred) {
                Result.retry()
            } catch (_: AnalysisSliceEnded) {
                Result.retry()
            } catch (_: Exception) {
                Result.retry()
            }
        }
}
