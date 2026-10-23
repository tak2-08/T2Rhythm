// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateMaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try { UpdateJournal.get(applicationContext).reconcile(); Result.success() }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
    companion object {
        fun enqueue(context: Context) { WorkManager.getInstance(context).enqueueUniqueWork("update-apk-maintenance", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UpdateMaintenanceWorker>().build()) }
    }
}
class UpdateInstalledReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) UpdateMaintenanceWorker.enqueue(context)
    }
}
