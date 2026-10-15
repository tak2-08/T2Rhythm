// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.video

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.Keep
import androidx.work.WorkInfo
import androidx.work.WorkManager
import chromahub.rhythm.app.features.local.data.metadata.PersonalArchive
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow

@Keep
data class DownloadRecord(
    val id: String,
    val kind: String,
    val key: String,
    val sourceId: String,
    val title: String,
    val artist: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val bytes: Long = 0,
    val totalBytes: Long = -1,
    val state: String = "ENQUEUED",
    val uri: String? = null,
    val fileBytes: Long = 0,
    val error: String? = null,
    val album: String = "",
    val service: String = "YOUTUBE",
)

/**
 * A compact permanent index outlives Compose, process restarts and WorkManager's retention window.
 */
object DownloadRegistry {
    const val TAG = "t2rhythm_media_download"
    val revision = MutableStateFlow(0L)
    private val gson = Gson()

    private fun root(context: Context) =
        File(context.filesDir, "music_metadata/downloads").apply { mkdirs() }

    @Synchronized
    fun save(context: Context, record: DownloadRecord) {
        require(runCatching { UUID.fromString(record.id) }.isSuccess)
        PersonalArchive.atomicWrite(
            File(root(context), record.id + ".json"),
            gson.toJson(record).toByteArray(),
        )
        if (record.kind == "AUDIO" && record.state == "SUCCEEDED") {
            val pointer =
                File(
                    root(context),
                    "sources/${kr.co.metadata.crawler.CatalogFileStore.hash(record.sourceId)}",
                )
            PersonalArchive.atomicWrite(pointer, record.id.toByteArray())
        }
        revision.value++
    }

    @Synchronized
    fun get(context: Context, id: String): DownloadRecord? =
        runCatching {
                require(UUID.fromString(id).toString() == id)
                val file = File(root(context), "$id.json")
                require(file.length() in 1..16_000)
                gson.fromJson(file.readText(), DownloadRecord::class.java)
            }
            .getOrNull()

    @Synchronized
    fun update(context: Context, id: String, change: (DownloadRecord) -> DownloadRecord) {
        get(context, id)?.let { save(context, change(it)) }
    }

    fun completedAudio(context: Context, sourceId: String): DownloadRecord? =
        runCatching {
                val pointer =
                    File(
                        root(context),
                        "sources/${kr.co.metadata.crawler.CatalogFileStore.hash(sourceId)}",
                    )
                require(pointer.length() in 1..40)
                get(context, pointer.readText())?.takeIf {
                    it.kind == "AUDIO" && it.sourceId == sourceId && it.state == "SUCCEEDED"
                }
            }
            .getOrNull()
            ?: recent(context, 1000).firstOrNull {
                it.kind == "AUDIO" && it.sourceId == sourceId && it.state == "SUCCEEDED"
            }

    fun recent(context: Context, limit: Int = 100): List<DownloadRecord> =
        root(context)
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .sortedByDescending { it.lastModified() }
            .take(limit.coerceIn(1, 1000))
            .mapNotNull { get(context, it.nameWithoutExtension) }
            .sortedWith(
                compareBy<DownloadRecord> {
                        if (it.state in setOf("SUCCEEDED", "FAILED", "CANCELLED")) 1 else 0
                    }
                    .thenByDescending { it.createdAt }
            )

    suspend fun recoverLegacy(context: Context) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val manager = WorkManager.getInstance(context)
            MusicVideoRepository(context).pendingLinks().forEach { video ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val work =
                    manager
                        .getWorkInfosForUniqueWork(
                            YouTubeVideoDownload.name(video.songKey, video.videoId!!)
                        )
                        .get(10, java.util.concurrent.TimeUnit.SECONDS)
                work
                    .filter { !it.state.isFinished }
                    .forEach { job ->
                        if (get(context, job.id.toString()) == null)
                            save(
                                context,
                                DownloadRecord(
                                    job.id.toString(),
                                    "VIDEO",
                                    video.songKey,
                                    video.videoId!!,
                                    video.title,
                                    state = job.state.name,
                                ),
                            )
                    }
            }
        }

    fun cancel(context: Context, id: String) {
        update(context, id) { if (it.state == "SUCCEEDED") it else it.copy(state = "CANCELLED") }
        WorkManager.getInstance(context).cancelWorkById(UUID.fromString(id))
        MediaTransfer.discard(context, id)
    }

    fun merge(record: DownloadRecord, work: WorkInfo?): DownloadRecord =
        if (work == null) record
        else
            record.copy(
                state =
                    if (
                        record.state == "SUCCEEDED" ||
                            record.state == "CANCELLED" ||
                            !work.state.isFinished
                    )
                        record.state
                    else work.state.name,
                bytes = maxOf(record.bytes, work.progress.getLong("bytes", 0)),
                totalBytes = work.progress.getLong("total", record.totalBytes),
                error = work.outputData.getString("download_error") ?: record.error,
            )
}

/**
 * Only small network buffers run in parallel. Device pressure reduces the next admission to one.
 */
internal object DownloadSlots {
    private var active = 0
    private val monitor = Any()

    fun capacity(context: Context): Int {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val memory = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return if (
            memory.isLowRamDevice ||
                context.cacheDir.usableSpace < 768L * 1024 * 1024 ||
                power.isPowerSaveMode ||
                Build.VERSION.SDK_INT >= 29 &&
                    power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        )
            1
        else 2
    }

    suspend fun <T> run(context: Context, block: suspend () -> T): T {
        while (true) {
            if (
                synchronized(monitor) {
                    if (active < capacity(context)) {
                        active++
                        true
                    } else false
                }
            )
                break
            delay(500)
        }
        try {
            return block()
        } finally {
            synchronized(monitor) { active-- }
        }
    }
}
