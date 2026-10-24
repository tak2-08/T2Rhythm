// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.content.Context
import androidx.work.*
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.experience.AnalysisBudget
import chromahub.rhythm.app.features.local.data.video.*
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SpeechModelStore {
    const val NAME = "vosk-model-small-ko-0.22"
    const val BYTES = 86_914_329L
    const val SHA256 = "eea36124087fed26c59996a4761519458e3bd185e8ea9d9865ad8760c4a1d989"
    const val URL = "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip"
    internal val access = Mutex()
    private val queueLock = Mutex()

    fun directory(context: Context) = File(context.noBackupFilesDir, "speech_models/$NAME")

    fun installed(context: Context) =
        runCatching {
                File(directory(context), "verified.sha256").readText() == SHA256 &&
                    File(directory(context), "am/final.mdl").length() > 0 &&
                    File(directory(context), "graph/HCLr.fst").length() > 0
            }
            .getOrDefault(false)

    suspend fun enqueue(context: Context) =
        withContext(Dispatchers.IO + NonCancellable) {
            queueLock.withLock {
                if (installed(context)) return@withContext
                val manager = WorkManager.getInstance(context)
                val existing =
                    manager
                        .getWorkInfosForUniqueWork("speech_model_ko")
                        .get(15, java.util.concurrent.TimeUnit.SECONDS)
                        .firstOrNull { !it.state.isFinished }
                val task =
                    OneTimeWorkRequestBuilder<SpeechModelDownloadWorker>()
                        .apply { existing?.let { setId(it.id) } }
                        .addTag(DownloadRegistry.TAG)
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .setRequiresStorageNotLow(true)
                                .build()
                        )
                        .build()
                if (existing != null) {
                    manager.updateWork(task).get(15, java.util.concurrent.TimeUnit.SECONDS)
                    return@withContext
                }
                DownloadRegistry.save(
                    context,
                    DownloadRecord(
                        task.id.toString(),
                        "MODEL",
                        NAME,
                        NAME,
                        context.getString(R.string.discovery_model_name),
                        totalBytes = BYTES,
                    ),
                )
                manager
                    .enqueueUniqueWork("speech_model_ko", ExistingWorkPolicy.KEEP, task)
                    .result
                    .get()
            }
        }

    suspend fun resumePending(context: Context) =
        withContext(Dispatchers.IO) {
            val pending =
                WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork("speech_model_ko")
                    .get(15, java.util.concurrent.TimeUnit.SECONDS)
            if (
                pending.any {
                    !it.state.isFinished &&
                        (it.constraints.requiresCharging() ||
                            it.constraints.requiredNetworkType == NetworkType.UNMETERED)
                }
            )
                enqueue(context)
        }

    suspend fun remove(context: Context) =
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(context).cancelAllWorkByTag("speech_alignment").result.get()
            access.withLock { directory(context).deleteRecursively() }
            DownloadRegistry.recent(context, 1000)
                .filter { it.kind == "MODEL" }
                .forEach { record ->
                    DownloadRegistry.update(context, record.id) {
                        it.copy(fileBytes = 0, uri = null)
                    }
                }
        }

    internal suspend fun install(context: Context, archive: File, taskId: String? = null) =
        withContext(AnalysisBudget.dispatcher) {
            val job = currentCoroutineContext()[Job]
            // Installation is user-requested I/O, not a charging-only analysis task.
            // Yield cooperatively without repeatedly restarting checksum/unzip on battery power.
            var yieldedAt = android.os.SystemClock.elapsedRealtime()
            suspend fun checkpoint() {
                job?.ensureActive()
                if (android.os.SystemClock.elapsedRealtime() - yieldedAt >= 16) {
                    delay(48)
                    yieldedAt = android.os.SystemClock.elapsedRealtime()
                }
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            archive.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    checkpoint()
                }
            }
            require(
                archive.length() == BYTES &&
                    digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == SHA256
            )
            val parent = directory(context).parentFile!!.apply { mkdirs() }
            require(parent.usableSpace > 400L * 1024 * 1024)
            val owner =
                taskId?.also { require(java.util.UUID.fromString(it).toString() == it) }
                    ?: java.util.UUID.randomUUID().toString()
            val stage =
                File(parent, "install-$owner").apply {
                    deleteRecursively()
                    mkdirs()
                }
            try {
                var total = 0L
                var entries = 0
                ZipInputStream(archive.inputStream().buffered()).use { zip ->
                    while (true) {
                        checkpoint()
                        val entry = zip.nextEntry ?: break
                        require(++entries <= 256 && entry.name.startsWith("$NAME/"))
                        val relative = entry.name.removePrefix("$NAME/")
                        val target = File(stage, relative)
                        require(
                            target.canonicalPath == stage.canonicalPath ||
                                target.canonicalPath.startsWith(
                                    stage.canonicalPath + File.separator
                                )
                        )
                        if (entry.isDirectory) target.mkdirs()
                        else {
                            target.parentFile!!.mkdirs()
                            target.outputStream().buffered().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    total += n
                                    require(total <= 384L * 1024 * 1024)
                                    output.write(buffer, 0, n)
                                    checkpoint()
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
                require(
                    File(stage, "am/final.mdl").isFile &&
                        File(stage, "conf/model.conf").isFile &&
                        File(stage, "graph/HCLr.fst").isFile
                )
                checkpoint()
                File(stage, "verified.sha256").writeText(SHA256)
                access.withLock {
                    job?.ensureActive()
                    val destination = directory(context)
                    val previous = File(parent, "$NAME.previous")
                    previous.deleteRecursively()
                    if (destination.exists()) check(destination.renameTo(previous))
                    try {
                        check(stage.renameTo(destination))
                        previous.deleteRecursively()
                    } catch (error: Exception) {
                        if (!destination.exists()) previous.renameTo(destination)
                        throw error
                    }
                }
            } finally {
                stage.deleteRecursively()
            }
        }
}

class SpeechModelDownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            DownloadSlots.run(applicationContext) {
                val taskId = id.toString()
                try {
                    val record =
                        DownloadRegistry.get(applicationContext, taskId)
                            ?: return@run Result.failure()
                    setForeground(MediaTransfer.foreground(applicationContext, record))
                    DownloadRegistry.update(applicationContext, taskId) {
                        it.copy(state = "RUNNING", error = null)
                    }
                    if (!SpeechModelStore.installed(applicationContext)) {
                        val file =
                            MediaTransfer.fetch(
                                applicationContext,
                                record,
                                SpeechModelStore.URL,
                                SpeechModelStore.SHA256,
                                100L * 1024 * 1024,
                            ) { bytes, total ->
                                DownloadRegistry.update(applicationContext, taskId) {
                                    it.copy(bytes = bytes, totalBytes = total)
                                }
                                setProgress(workDataOf("bytes" to bytes, "total" to total))
                            }
                        DownloadRegistry.update(applicationContext, taskId) {
                            it.copy(state = "INSTALLING", error = null)
                        }
                        SpeechModelStore.install(applicationContext, file, taskId)
                    }
                    currentCoroutineContext().ensureActive()
                    val bytes =
                        SpeechModelStore.directory(applicationContext)
                            .walkTopDown()
                            .filter { it.isFile }
                            .sumOf { it.length() }
                    DownloadRegistry.update(applicationContext, taskId) {
                        it.copy(
                            state = "SUCCEEDED",
                            bytes = SpeechModelStore.BYTES,
                            totalBytes = SpeechModelStore.BYTES,
                            fileBytes = bytes,
                            uri =
                                android.net.Uri.fromFile(
                                        SpeechModelStore.directory(applicationContext)
                                    )
                                    .toString(),
                        )
                    }
                    MediaTransfer.discard(applicationContext, taskId)
                    EnrichmentLearningWorker.enqueue(applicationContext)
                    Result.success()
                } catch (cancel: CancellationException) {
                    DownloadRegistry.update(applicationContext, taskId) {
                        if (it.state in setOf("SUCCEEDED", "CANCELLED")) it
                        else it.copy(state = "ENQUEUED")
                    }
                    throw cancel
                } catch (_: chromahub.rhythm.app.features.local.data.experience.AnalysisDeferred) {
                    DownloadRegistry.update(applicationContext, taskId) {
                        it.copy(state = "ENQUEUED")
                    }
                    Result.retry()
                } catch (error: Exception) {
                    val retry = error is java.io.IOException && runAttemptCount < 3
                    DownloadRegistry.update(applicationContext, taskId) {
                        it.copy(
                            state = if (retry) "ENQUEUED" else "FAILED",
                            error = error.javaClass.simpleName,
                        )
                    }
                    if (retry) Result.retry() else Result.failure()
                }
            }
        }
}
