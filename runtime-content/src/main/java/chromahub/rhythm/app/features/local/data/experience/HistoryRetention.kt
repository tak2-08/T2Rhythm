// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import android.content.Context
import androidx.work.*
import chromahub.rhythm.app.features.local.data.metadata.PersonalArchive
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Original music, lyrics, explicit favourites and their review history are never pruned here. */
object HistoryRetention {
    const val DETAIL_DAYS = 120
    private val lock = Mutex()
    @Volatile internal var running = false
    private val gson = Gson()

    private fun prefs(context: Context) =
        context.getSharedPreferences("history_retention", Context.MODE_PRIVATE)

    fun savedBytes(context: Context) = prefs(context).getLong("saved_bytes", 0)

    fun lastRun(context: Context) = prefs(context).getLong("last_run", 0)

    internal fun compact(event: RecommendationEvent) = RecommendationEvent(
        id=event.id,at=event.at,type=event.type,key=event.key,schema=event.schema,
        features=event.features.orEmpty(),reward=event.reward,prediction=event.prediction,
        algorithm=event.algorithm ?: "legacy",startedAt=event.startedAt,
        completionRatio=event.completionRatio,immediateRepeat=event.immediateRepeat,compacted=true,
    )

    internal suspend fun run(context: Context, budget: AnalysisBudget) =
        lock.withLock {
            // The initial and daily requests can become ready together after a long power wait.
            if (System.currentTimeMillis() - lastRun(context) < TimeUnit.HOURS.toMillis(24))
                return@withLock
            running = true
            try {
                val cutoff = System.currentTimeMillis() - DETAIL_DAYS * 86_400_000L
                val ledger = ListeningLedger.get(context)
                budget.checkpoint()
                ledger.ensureRecovered()
                ledger.compactBefore(cutoff, budget)
                val before = journalBytes(context)
                ledger.rewriteCompactedJournals(cutoff, budget)
                compactRecommendations(context, cutoff, budget)
                RecommendationTrainingIndex(context).compactPayloads(cutoff, budget)
                val saved = (before - journalBytes(context)).coerceAtLeast(0)
                val preferences = prefs(context)
                check(
                    preferences
                        .edit()
                        .putLong("saved_bytes", savedBytes(context) + saved)
                        .putLong("last_run", System.currentTimeMillis())
                        .commit()
                )
                ledger.invalidate()
                RecommendationTrainingWorker.enqueue(context)
                PersonalArchive.enqueue(context)
            } finally {
                running = false
            }
        }

    private fun journalBytes(context: Context): Long =
        listOf(
                File(context.filesDir, "listening_history"),
                File(context.filesDir, "music_metadata/recommendations"),
            )
            .sumOf { root ->
                root.listFiles().orEmpty().filter { it.extension == "jsonl" }.sumOf { it.length() }
            }

    private fun compactRecommendations(context: Context, cutoff: Long, budget: AnalysisBudget) {
        val root = File(context.filesDir, "music_metadata/recommendations")
        val preferences = prefs(context)
        for (file in root.listFiles().orEmpty()) {
            if (file.extension != "jsonl") continue
            val day =
                runCatching { java.time.LocalDate.parse(file.name.take(10)) }.getOrNull()
                    ?: continue
            if (
                day.plusDays(2).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() >=
                    cutoff
            )
                continue
            budget.blockingCheckpoint()
            val stamp = "${file.length()}:${file.lastModified()}"
            val key = "recommendation:${file.name}"
            if (preferences.getString(key, null) == stamp) continue
            val pending = File.createTempFile("recommendation-summary-", ".tmp", root)
            try {
                java.io.FileOutputStream(pending).use { output ->
                    output.bufferedWriter().use { writer ->
                        file.forEachLine { line ->
                            budget.blockingCheckpoint()
                            val event =
                                runCatching {
                                        if (line.length <= 64_000)
                                            gson.fromJson(line, RecommendationEvent::class.java)
                                        else null
                                    }
                                    .getOrNull()
                            writer.write(
                                if (event != null && event.at < cutoff) gson.toJson(compact(event))
                                else line
                            )
                            writer.newLine()
                        }
                        writer.flush()
                        output.fd.sync()
                    }
                }
                synchronized(RecommendationLearning.get(context)) {
                    if (stamp == "${file.length()}:${file.lastModified()}") {
                        check(pending.renameTo(file))
                        check(
                            preferences
                                .edit()
                                .putString(key, "${file.length()}:${file.lastModified()}")
                                .commit()
                        )
                    }
                }
            } finally {
                pending.delete()
            }
        }
    }

    fun enqueue(context: Context) {
        val constraints =
            Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniquePeriodicWork(
            "history_retention",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<HistoryRetentionWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(20, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build(),
        )
        if(System.currentTimeMillis()-lastRun(context)<TimeUnit.HOURS.toMillis(24)) return
        manager.enqueueUniqueWork(
            "history_retention_initial",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<HistoryRetentionWorker>()
                .setInitialDelay(20, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build(),
        )
    }
}

class HistoryRetentionWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(AnalysisBudget.dispatcher) {
            try {
                val budget = AnalysisBudget(applicationContext, { !isStopped }, 90_000)
                budget.checkpoint()
                HistoryRetention.run(applicationContext, budget)
                Result.success()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: AnalysisDeferred) {
                Result.retry()
            } catch (_: AnalysisSliceEnded) {
                Result.retry()
            } catch (error: Exception) {
                android.util.Log.w("HistoryRetention", "History kept; summary will retry", error)
                Result.retry()
            }
        }
}
