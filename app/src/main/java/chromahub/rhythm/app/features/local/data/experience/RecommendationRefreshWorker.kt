// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import android.content.Context
import androidx.work.*
import chromahub.rhythm.app.features.local.di.LocalMusicModule
import chromahub.rhythm.app.shared.data.model.AppSettings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

class RecommendationRefreshWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val before =
                RecommendationShelf.current(applicationContext)
                    ?: return@withContext Result.success()
            if (!RecommendationShelf.refreshDue(applicationContext, before))
                return@withContext Result.success()
            val songs = LocalMusicModule.provideMusicRepository(applicationContext).loadSongs()
            val favorites =
                runCatching {
                        com.google.gson
                            .Gson()
                            .fromJson(
                                AppSettings.getInstance(applicationContext).favoriteSongs.value,
                                Array<String>::class.java,
                            )
                            ?.toList()
                    }
                    .getOrNull()
                    .orEmpty()
            val seed = System.currentTimeMillis() xor System.nanoTime()
            val picks =
                PersonalCollections.generate(
                    applicationContext,
                    songs,
                    favorites,
                    null,
                    before.mode,
                    20,
                    before.genre,
                    seed,
                )
            RecommendationShelf.createIfCurrent(
                applicationContext,
                before.id,
                before.mode,
                before.genre,
                picks.map { it.uri.toString() },
                seed,
            )
            Result.success()
        }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "recommendation_refresh",
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<RecommendationRefreshWorker>(1, TimeUnit.HOURS)
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiresBatteryNotLow(true)
                                .setRequiresStorageNotLow(true)
                                .build()
                        )
                        .build(),
                )
        }
    }
}
