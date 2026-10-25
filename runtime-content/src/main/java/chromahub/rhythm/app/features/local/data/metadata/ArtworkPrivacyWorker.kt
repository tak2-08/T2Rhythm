// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.metadata

import android.content.Context
import androidx.work.*
import chromahub.rhythm.app.features.local.data.experience.AnalysisBudget
import chromahub.rhythm.app.features.local.data.experience.AnalysisDeferred
import chromahub.rhythm.app.features.local.data.experience.AnalysisSliceEnded
import kotlinx.coroutines.withContext

class ArtworkPrivacyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(AnalysisBudget.dispatcher) { try {
        val budget = AnalysisBudget(applicationContext, { !isStopped }, 15_000)
        budget.checkpoint()
        PersonalArchive(applicationContext).syncBackground(budget)
        Result.success()
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: AnalysisDeferred) { Result.retry() }
    catch (_: AnalysisSliceEnded) { Result.retry() }
    catch (_: Exception) { Result.failure() }
    }
    companion object {
        fun enqueue(context: Context, continuation: Boolean = false) {
            val prefs = context.getSharedPreferences("personal_archive", Context.MODE_PRIVATE)
            if (!continuation && prefs.getString("privacy_migration_target", null) == (prefs.getString("tree", null) ?: "default")) return
            WorkManager.getInstance(context).enqueueUniqueWork("private_artwork_migration_v1",
                if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ArtworkPrivacyWorker>().setConstraints(Constraints.Builder()
                    .setRequiresStorageNotLow(true).setRequiresBatteryNotLow(true).build()).build())
        }
    }
}
