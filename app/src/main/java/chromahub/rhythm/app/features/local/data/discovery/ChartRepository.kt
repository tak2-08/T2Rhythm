// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.content.Context
import androidx.annotation.Keep
import chromahub.rhythm.app.features.local.data.metadata.PersonalArchive
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kr.co.metadata.crawler.*

@Keep
data class ChartSnapshot(val provider: Provider, val fetchedAt: Long, val tracks: List<ChartTrack>)

class ChartRepository(private val context: Context) {
    companion object {
        private val lock = Mutex()
    }

    private val gson = Gson()

    private fun file(provider: Provider) =
        File(context.filesDir, "music_metadata/charts/${provider.name}.json")

    fun cached(provider: Provider): ChartSnapshot? =
        runCatching {
                val source = file(provider)
                require(source.length() in 1..1_000_000)
                gson.fromJson(source.readText(), ChartSnapshot::class.java).takeIf {
                    it.provider == provider && it.tracks.size in 1..100
                }
            }
            .getOrNull()

    suspend fun refresh(provider: Provider, force: Boolean = false): ChartSnapshot =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val old = cached(provider)
                val age = old?.let { System.currentTimeMillis() - it.fetchedAt } ?: Long.MAX_VALUE
                // Even explicit tapping cannot turn the page into a crawler request storm.
                if (old != null && age in 0..if (force) 5 * 60_000L else 3600_000L)
                    return@withLock old
                val tracks = CatalogCharts().fetch(provider)
                check(tracks.isNotEmpty()) { "Chart unavailable" }
                ChartSnapshot(provider, System.currentTimeMillis(), tracks).also {
                    PersonalArchive.atomicWrite(file(provider), gson.toJson(it).toByteArray())
                }
            }
        }
}
