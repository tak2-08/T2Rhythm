// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.shared.data.repository

import android.content.Context
import androidx.annotation.Keep
import chromahub.rhythm.app.features.local.data.metadata.PersonalArchive
import com.google.gson.Gson
import java.io.File

@Keep
internal data class CachedPlaybackSummary(
    val schema: Int = 1,
    val key: String,
    val version: String,
    val library: String,
    val builtAt: Long,
    val summary: PlaybackStatsRepository.PlaybackStatsSummary,
)

/** Small materialized results survive process/cache eviction; journals remain authoritative. */
internal class PlaybackSummaryCache(context: Context) {
    private val root = File(context.filesDir, "listening_summaries")
    private val gson = Gson()

    private fun file(key: String) =
        File(root, kr.co.metadata.crawler.CatalogFileStore.hash(key) + ".json")

    fun read(key: String, generation: String): CachedPlaybackSummary? =
        runCatching {
                val file = file(key)
                require(file.length() in 1..128_000)
                gson.fromJson(file.readText(), CachedPlaybackSummary::class.java).takeIf {
                    it.schema == 1 &&
                        it.key == key &&
                        it.version.substringBefore(':') == generation &&
                        System.currentTimeMillis() - it.builtAt in 0..7 * 86_400_000L
                }
            }
            .getOrNull()

    fun save(entry: CachedPlaybackSummary) {
        PersonalArchive.atomicWrite(file(entry.key), gson.toJson(entry).toByteArray())
        root
            .listFiles()
            ?.filter { it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(12)
            ?.forEach { it.delete() }
    }

    fun clear() {
        root.deleteRecursively()
    }
}
