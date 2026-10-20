// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.metadata

import android.content.Context
import androidx.work.*
import chromahub.rhythm.app.features.local.di.LocalMusicModule
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.app.shared.data.model.Song
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.TimeUnit
import kr.co.metadata.crawler.CatalogFileStore

/** Small resumable batches; background work never opens permission dialogs. */
class FileNameWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = AppSettings.getInstance(applicationContext)
        val service = FileNameService(applicationContext)
        try {
            service.recover()
            if (!settings.autoFileNames.value) return@withContext Result.success()
            val format = settings.fileNameFormat.value
            val songs = LocalMusicModule.provideMusicRepository(applicationContext).loadSongs().sortedBy { it.uri.toString() }
            val prefs = applicationContext.getSharedPreferences("file_name_progress", Context.MODE_PRIVATE)
            val signature = CatalogFileStore.hash(format + songs.joinToString("|") { it.uri.toString() })
            val start = if (prefs.getString("signature", null) == signature) prefs.getInt("offset", 0).coerceIn(0, songs.size) else 0
            var next = start
            val started = android.os.SystemClock.elapsedRealtime()
            for (song in songs.drop(start).take(64)) {
                currentCoroutineContext().ensureActive()
                if (!settings.autoFileNames.value || settings.fileNameFormat.value != format) break
                val id = "checked:" + CatalogFileStore.hash(song.uri.toString())
                val sourceStamp = stamp(song, format)
                val catalog = CatalogEnrichmentService.get(applicationContext)
                if (prefs.getString(id, null) != sourceStamp && !catalog.audioBusy(song)) {
                    try {
                        val plan = service.plan(song, format)
                        val outcome = plan?.let { service.rename(it) }
                        if (outcome?.state != "BUSY" && !catalog.audioBusy(song)) prefs.edit().putString(id, outcome?.after?.let { stamp(it, format) } ?: sourceStamp).apply()
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { /* Keep other independent files moving; manual preview can retry. */ }
                }
                next++
                check(prefs.edit().putString("signature", signature).putInt("offset", next).commit())
                setProgress(workDataOf("done" to next, "total" to songs.size, "pending" to service.pendingCount()))
                if (android.os.SystemClock.elapsedRealtime() - started > 40_000) break
                delay(30)
            }
            if (next < songs.size && settings.autoFileNames.value) enqueue(applicationContext, true)
            else prefs.edit().putInt("offset", 0).apply()
            Result.success()
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { Result.retry() }
    }
    private fun stamp(song: Song, format: String): String {
        val file = song.path?.let(::File)
        return "$format:${song.path}:${song.dateModified}:${file?.lastModified()}:${file?.length()}"
    }
    companion object {
        const val NAME = "file_name_automation"
        fun enqueue(context: Context, continuation: Boolean = false) {
            if (!AppSettings.getInstance(context).autoFileNames.value) return
            val manager = WorkManager.getInstance(context)
            val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()
            manager.enqueueUniquePeriodicWork("file_name_periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FileNameWorker>(12, TimeUnit.HOURS).setConstraints(constraints).build())
            manager.enqueueUniqueWork(NAME, if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<FileNameWorker>().setConstraints(constraints).setInitialDelay(if (continuation) 1 else 30, TimeUnit.SECONDS).build())
        }
        fun stop(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME); WorkManager.getInstance(context).cancelUniqueWork("file_name_periodic") }
    }
}
