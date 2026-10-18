// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withContext

class RecommendationTrainingWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(AnalysisBudget.dispatcher) {
            try {
                if (!AnalysisBudget(applicationContext).allowed()) return@withContext Result.retry()
                RecommendationLearning.get(applicationContext).rebuild()
                Result.success()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: AnalysisSliceEnded) {
                enqueue(applicationContext, true)
                Result.success()
            } catch (_: AnalysisDeferred) {
                Result.retry()
            } catch (_: Exception) {
                Result.failure()
            }
        }

    companion object {
        private var lastEnqueue = Long.MIN_VALUE
        private var lastOperation: Operation? = null

        @Synchronized
        fun enqueue(context: Context, continuation: Boolean = false): Operation {
            val now = android.os.SystemClock.elapsedRealtime()
            if (
                !continuation &&
                    lastEnqueue != Long.MIN_VALUE &&
                    now - lastEnqueue < 15 * 60_000L &&
                    lastOperation != null
            )
                return lastOperation!!
            lastEnqueue = now
            val manager = WorkManager.getInstance(context)
            val constraints =
                Constraints.Builder()


                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            manager.enqueueUniquePeriodicWork(
                "recommendation_training_periodic_v6",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<RecommendationTrainingWorker>(6, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build(),
            )
            return manager
                .enqueueUniqueWork(
                    "recommendation_training_v4",
                    if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE
                    else ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<RecommendationTrainingWorker>()
                        .setConstraints(
                            Constraints.Builder()


                                .setRequiresBatteryNotLow(true)
                                .setRequiresStorageNotLow(true)
                                .build()
                        )
                        .build(),
                )
                .also { lastOperation = it }
        }
    }
}
