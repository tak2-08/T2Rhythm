// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.video

import android.content.Context
import android.net.Uri
import androidx.work.*
import chromahub.rhythm.app.network.NetworkClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

/** NewPipeExtractor GPL-3.0+: native Android extraction, no bundled Python or re-encoding. */
object YouTubeVideoDownload {
    const val MAX_BYTES = 96L * 1024 * 1024
    internal val extraction = Mutex()
    internal val installation = Mutex()
    private val enqueueLock = Mutex()
    private var initialized = false
    private val extractionScope = ThreadLocal<CoroutineScope?>()

    internal suspend fun <T> extract(block: () -> T): T =
        withContext(Dispatchers.IO) {
            extraction.withLock {
                initialize()
                withContext(
                    extractionScope.asContextElement(CoroutineScope(currentCoroutineContext()))
                ) {
                    currentCoroutineContext().ensureActive()
                    block()
                }
            }
        }

    fun name(key: String, id: String) =
        "youtube_video:${kr.co.metadata.crawler.CatalogFileStore.hash(key)}:$id"

    private fun songTag(key: String) =
        "video_song:${kr.co.metadata.crawler.CatalogFileStore.hash(key)}"

    suspend fun enqueue(
        context: Context,
        key: String,
        id: String,
        title: String,
        mobile: Boolean = false,
        force: Boolean = false,
    ): java.util.UUID =
        withContext(Dispatchers.IO + NonCancellable) {
            enqueueLock.withLock {
                require(MusicVideoLinks.validId(id))
                val manager = WorkManager.getInstance(context)
                val existing =
                    manager
                        .getWorkInfosForUniqueWork(name(key, id))
                        .get(15, TimeUnit.SECONDS)
                        .lastOrNull { !it.state.isFinished }
                if (!force && existing != null) {
                    if (DownloadRegistry.get(context, existing.id.toString()) == null)
                        DownloadRegistry.save(
                            context,
                            DownloadRecord(existing.id.toString(), "VIDEO", key, id, title),
                        )
                    return@withLock existing.id
                }
                manager
                    .getWorkInfosByTag(songTag(key))
                    .get(15, TimeUnit.SECONDS)
                    .filter { !it.state.isFinished }
                    .forEach {
                        DownloadRegistry.update(context, it.id.toString()) { record ->
                            record.copy(state = "CANCELLED")
                        }
                    }
                manager.cancelAllWorkByTag(songTag(key)).result.get(15, TimeUnit.SECONDS)
                val request =
                    OneTimeWorkRequestBuilder<YouTubeVideoDownloadWorker>()
                        .addTag(songTag(key))
                        .addTag(DownloadRegistry.TAG)
                        .setInputData(workDataOf("key" to key, "id" to id, "title" to title))
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(
                                    if (mobile) NetworkType.CONNECTED else NetworkType.UNMETERED
                                )
                                .setRequiresStorageNotLow(true)
                                .setRequiresBatteryNotLow(true)
                                .build()
                        )
                        .build()
                DownloadRegistry.save(
                    context,
                    DownloadRecord(request.id.toString(), "VIDEO", key, id, title),
                )
                manager
                    .enqueueUniqueWork(name(key, id), ExistingWorkPolicy.REPLACE, request)
                    .result
                    .get(15, TimeUnit.SECONDS)
                request.id
            }
        }

    fun cancel(context: Context, key: String) {
        DownloadRegistry.recent(context, 1000)
            .filter {
                it.kind == "VIDEO" &&
                    it.key == key &&
                    it.state !in setOf("SUCCEEDED", "FAILED", "CANCELLED")
            }
            .forEach { DownloadRegistry.cancel(context, it.id) }
        WorkManager.getInstance(context).cancelAllWorkByTag(songTag(key))
    }

    @Synchronized
    internal fun initialize() {
        if (initialized) return
        NewPipe.init(
            object : org.schabi.newpipe.extractor.downloader.Downloader() {
                override fun execute(
                    request: org.schabi.newpipe.extractor.downloader.Request
                ): org.schabi.newpipe.extractor.downloader.Response {
                    val builder = okhttp3.Request.Builder().url(request.url())
                    request.headers().forEach { (name, values) ->
                        values.forEach { builder.addHeader(name, it) }
                    }
                    builder.method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                    val call = NetworkClient.genericHttpClient.newCall(builder.build())
                    val scope = extractionScope.get()
                    val cancellation =
                        scope?.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                            try {
                                awaitCancellation()
                            } finally {
                                call.cancel()
                            }
                        }
                    try {
                        return call.execute().use { response ->
                            require(response.body.contentLength() <= 8 * 1024 * 1024) {
                                "Extractor response too large"
                            }
                            val input = response.body.byteStream()
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 8 * 1024 * 1024)
                                output.write(buffer, 0, count)
                            }
                            org.schabi.newpipe.extractor.downloader.Response(
                                response.code,
                                response.message,
                                response.headers.toMultimap(),
                                output.toString(Charsets.UTF_8.name()),
                                response.request.url.toString(),
                            )
                        }
                    } finally {
                        cancellation?.cancel()
                        scope?.coroutineContext?.ensureActive()
                    }
                }
            },
            Localization("ko", "KR"),
        )
        initialized = true
    }

    internal fun choose(streams: List<VideoStream>, seconds: Long): VideoStream {
        val valid =
            streams.filter { stream ->
                stream.isUrl &&
                    stream.isVideoOnly &&
                    stream.height in 1..720 &&
                    stream.fps <= 30 &&
                    stream.format?.suffix in setOf("webm", "mp4") &&
                    runCatching {
                            val url = java.net.URI(stream.content)
                            url.scheme == "https" && url.host?.endsWith(".googlevideo.com") == true
                        }
                        .getOrDefault(false)
            }
        val compact =
            valid
                .filter { it.bitrate <= 0 || it.bitrate.toLong() * seconds / 8 < 48L * 1024 * 1024 }
                .ifEmpty { valid }
        return compact
            .sortedWith(
                compareByDescending<VideoStream> { it.height }
                    .thenBy { if (it.format?.suffix == "webm") 0 else 1 }
                    .thenBy { it.bitrate.takeIf { rate -> rate > 0 } ?: Int.MAX_VALUE }
            )
            .firstOrNull() ?: error("No compatible video-only stream at or below 720p")
    }
}

class YouTubeVideoDownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            DownloadSlots.run(applicationContext) {
                val key = inputData.getString("key") ?: return@run Result.failure()
                val sourceId =
                    inputData.getString("id")?.takeIf(MusicVideoLinks::validId)
                        ?: return@run Result.failure()
                val taskId = id.toString()
                val repository = MusicVideoRepository(applicationContext)
                if (DownloadRegistry.get(applicationContext, taskId) == null)
                    DownloadRegistry.save(
                        applicationContext,
                        DownloadRecord(
                            taskId,
                            "VIDEO",
                            key,
                            sourceId,
                            inputData.getString("title").orEmpty(),
                        ),
                    )
                if (
                    repository.get(key)?.let { it.source != "youtube" || it.videoId != sourceId } !=
                        false
                ) {
                    DownloadRegistry.update(applicationContext, taskId) {
                        it.copy(state = "CANCELLED")
                    }
                    return@run Result.success()
                }
                var completed = false
                try {
                    setForeground(
                        MediaTransfer.foreground(
                            applicationContext,
                            DownloadRegistry.get(applicationContext, taskId)!!,
                        )
                    )
                    DownloadRegistry.update(applicationContext, taskId) {
                        it.copy(state = "RUNNING", error = null)
                    }
                    YouTubeVideoDownload.initialize()
                    val info =
                        YouTubeVideoDownload.extract {
                            StreamInfo.getInfo(
                                ServiceList.YouTube,
                                "https://www.youtube.com/watch?v=$sourceId",
                            )
                        }
                    val stream = YouTubeVideoDownload.choose(info.videoOnlyStreams, info.duration)
                    val identity =
                        "$sourceId:${stream.height}:${stream.fps}:${stream.format?.suffix}:${stream.bitrate}"
                    val temporary =
                        MediaTransfer.fetch(
                            applicationContext,
                            DownloadRegistry.get(applicationContext, taskId)!!,
                            stream.content,
                            identity,
                            YouTubeVideoDownload.MAX_BYTES,
                        ) { bytes, total ->
                            DownloadRegistry.update(applicationContext, taskId) {
                                it.copy(bytes = bytes, totalBytes = total)
                            }
                            setProgress(
                                workDataOf(
                                    "bytes" to bytes,
                                    "total" to total,
                                    "percent" to
                                        if (total > 0) (bytes * 100 / total).toInt() else -1,
                                )
                            )
                        }
                    currentCoroutineContext().ensureActive()
                    check(DownloadRegistry.get(applicationContext, taskId)?.state != "CANCELLED")
                    val imported =
                        YouTubeVideoDownload.installation.withLock {
                            repository
                                .import(
                                    key,
                                    Uri.fromFile(temporary),
                                    inputData.getString("title").orEmpty(),
                                    persist = false,
                                )
                                .copy(
                                    videoId = sourceId,
                                    width = stream.width,
                                    height = stream.height,
                                )
                        }
                    currentCoroutineContext().ensureActive()
                    check(repository.installDownload(key, sourceId, imported)) {
                        "Video link changed"
                    }
                    DownloadRegistry.update(applicationContext, taskId) {
                        it.copy(
                            state = "SUCCEEDED",
                            bytes = temporary.length(),
                            totalBytes = temporary.length(),
                            uri = repository.uri(imported)?.toString(),
                            fileBytes = imported.bytes,
                        )
                    }
                    completed = true
                    Result.success()
                } catch (cancel: CancellationException) {
                    val explicit =
                        DownloadRegistry.get(applicationContext, taskId)?.state == "CANCELLED"
                    if (explicit) MediaTransfer.discard(applicationContext, taskId)
                    else
                        DownloadRegistry.update(applicationContext, taskId) {
                            if (it.state == "SUCCEEDED") it else it.copy(state = "ENQUEUED")
                        }
                    throw cancel
                } catch (error: Exception) {
                    if (DownloadRegistry.get(applicationContext, taskId)?.state == "CANCELLED") {
                        MediaTransfer.discard(applicationContext, taskId)
                        return@run Result.success()
                    }
                    val retry = error is java.io.IOException && runAttemptCount < 3
                    DownloadRegistry.update(applicationContext, taskId) {
                        it.copy(
                            state = if (retry) "ENQUEUED" else "FAILED",
                            error = error.javaClass.simpleName,
                        )
                    }
                    if (retry) Result.retry()
                    else {
                        MediaTransfer.discard(applicationContext, taskId)
                        Result.failure(workDataOf("download_error" to error.javaClass.simpleName))
                    }
                } finally {
                    if (completed) MediaTransfer.discard(applicationContext, taskId)
                }
            }
        }
}
