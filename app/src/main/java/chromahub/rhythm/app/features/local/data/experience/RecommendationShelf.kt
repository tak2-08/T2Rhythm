// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import android.content.Context
import androidx.annotation.Keep
import chromahub.rhythm.app.features.local.data.metadata.PersonalArchive
import com.google.gson.Gson
import java.io.File

@Keep data class MixSnapshot(val id: String, val mode: PersonalCollection, val genre: String?, val createdAt: Long,
    val seed: Long, val songKeys: List<String>, val features: Map<String, List<Double>> = emptyMap()) {
    val feedbackKey get() = "mix:$id"
}
/** Immutable generations: feedback and saved drawer entries always refer to the exact displayed queue. */
object RecommendationShelf {
    val selection = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val gson = Gson()
    private fun root(context: Context) = File(context.filesDir, "music_metadata/personalization/mixes").apply { mkdirs() }
    private fun read(file: File): MixSnapshot? = runCatching {
        require(file.length() in 1..1_000_000)
        gson.fromJson(file.readText(), MixSnapshot::class.java).also { require(it.id.matches(Regex("[a-f0-9-]{36}")) && it.songKeys.size in 1..500) }
    }.getOrNull()
    fun current(context: Context): MixSnapshot? {
        val id = context.getSharedPreferences("recommendation_shelf", Context.MODE_PRIVATE).getString("current", null) ?: return null
        if (!id.matches(Regex("[a-f0-9-]{36}"))) return null
        return read(File(root(context), "$id.json"))
    }
    fun recent(context: Context, limit: Int = 50): List<MixSnapshot> = root(context).listFiles { f -> f.extension == "json" }.orEmpty()
        .sortedByDescending { it.lastModified() }.take(limit.coerceIn(1, 100)).mapNotNull(::read).sortedByDescending { it.createdAt }
    @Synchronized fun create(context: Context, mode: PersonalCollection, genre: String?, keys: List<String>, seed: Long): MixSnapshot? {
        if (keys.isEmpty()) return null
        val selected = keys.distinct().take(500)
        val snapshot = MixSnapshot(java.util.UUID.randomUUID().toString(), mode, genre, System.currentTimeMillis(), seed, selected,
            RecommendationLearning.get(context).captureFeatures(selected))
        PersonalArchive.atomicWrite(File(root(context), "${snapshot.id}.json"), gson.toJson(snapshot).toByteArray())
        select(context, snapshot)
        PersonalArchive.enqueue(context)
        return snapshot
    }
    @Synchronized fun interacted(context: Context, id: String) { context.getSharedPreferences("recommendation_shelf", Context.MODE_PRIVATE).edit().putLong("interaction:$id", System.currentTimeMillis()).apply() }
    fun refreshDue(context: Context, snapshot: MixSnapshot, now: Long = System.currentTimeMillis()): Boolean {
        val interval=PersonalCollections.options(context).refreshHours.takeIf { it in 1..48 } ?: 6
        val last=context.getSharedPreferences("recommendation_shelf", Context.MODE_PRIVATE).getLong("interaction:${snapshot.id}",snapshot.createdAt)
        return now-snapshot.createdAt >= interval*3600_000L && now-last >= 30*60_000L
    }
    @Synchronized fun createIfCurrent(context: Context, expected: String, mode: PersonalCollection, genre: String?, keys: List<String>, seed: Long): MixSnapshot? {
        val existing=current(context) ?: return null
        if (existing.id != expected || !refreshDue(context,existing)) return null
        return create(context,mode,genre,keys,seed)
    }
    @Synchronized fun select(context: Context, snapshot: MixSnapshot) {
        check(context.getSharedPreferences("recommendation_shelf", Context.MODE_PRIVATE).edit().putString("current", snapshot.id).commit())
        selection.value=snapshot.id
    }
}
