// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.video

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.metadata.PersonalArchive
import chromahub.rhythm.app.network.NetworkClient
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.Request

internal object MediaTransfer {
    private const val CHANNEL = "media_downloads"

    @androidx.annotation.Keep
    private data class Resume(val identity: String, val etag: String, val completedBytes: Long = 0)

    fun foreground(context: Context, record: DownloadRecord): ForegroundInfo {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.discovery_downloads),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val notification =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(record.title)
                .setContentText(context.getString(R.string.discovery_downloading))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(
                    100,
                    if (record.totalBytes > 0)
                        (record.bytes * 100 / record.totalBytes).toInt().coerceIn(0, 100)
                    else 0,
                    record.totalBytes <= 0,
                )
                .apply {
                    if (launch != null)
                        setContentIntent(
                            PendingIntent.getActivity(
                                context,
                                0,
                                launch,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                            )
                        )
                }
                .build()
        val id = record.id.hashCode() and 0x7fffffff
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id, notification)
    }

    /** Resume only when the server validates a strong ETag and exact byte range. */
    suspend fun fetch(
        context: Context,
        record: DownloadRecord,
        url: String,
        identity: String,
        maxBytes: Long,
        httpClient: okhttp3.OkHttpClient = NetworkClient.genericHttpClient,
        directory: File? = null,
        progress: suspend (Long, Long) -> Unit,
    ): File =
        withContext(Dispatchers.IO) {
            require(java.net.URI(url).let { it.scheme == "https" && it.userInfo == null })
            val root = (directory ?: File(context.cacheDir, "media_transfers")).apply { mkdirs() }
            val target = File(root, "${record.id}.part")
            val metadata = File(root, "${record.id}.json")
            val resume =
                runCatching {
                        require(metadata.length() in 1..4096)
                        Gson().fromJson(metadata.readText(), Resume::class.java)
                    }
                    .getOrNull()
            if (
                resume?.identity == identity &&
                    resume.completedBytes > 0 &&
                    resume.completedBytes <= maxBytes &&
                    target.length() == resume.completedBytes
            ) {
                progress(target.length(), target.length())
                return@withContext target
            }
            val offset =
                if (
                    resume?.identity == identity &&
                        resume.etag.startsWith("\"") &&
                        target.length() in 1 until maxBytes
                )
                    target.length()
                else 0L
            if (offset == 0L) {
                target.delete()
                metadata.delete()
            }
            require(root.usableSpace > maxBytes - offset + 16 * 1024 * 1024) { "Storage is low" }
            val request =
                Request.Builder()
                    .url(url)
                    .header("Accept-Encoding", "identity")
                    .apply {
                        if (offset > 0) {
                            header("Range", "bytes=$offset-")
                            header("If-Range", resume!!.etag)
                        }
                    }
                    .build()
            val client =
                httpClient
                    .newBuilder()
                    .callTimeout(0, TimeUnit.MILLISECONDS)
                    .readTimeout(40, TimeUnit.SECONDS)
                    .build()
            val call = client.newCall(request)
            val cancellation =
                CoroutineScope(currentCoroutineContext()).launch(
                    Dispatchers.IO,
                    start = CoroutineStart.UNDISPATCHED,
                ) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
            try {
                call.execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val continued =
                        offset > 0 &&
                            response.code == 206 &&
                            response.header("ETag") == resume?.etag &&
                            response.header("Content-Range")?.startsWith("bytes $offset-") == true
                    check(response.code != 206 || continued) { "Unexpected partial response" }
                    val start = if (continued) offset else 0L
                    val length = response.body.contentLength()
                    val range =
                        if (continued)
                            Regex("bytes (\\d+)-(\\d+)/(\\d+)")
                                .matchEntire(response.header("Content-Range").orEmpty())
                        else null
                    if (continued)
                        check(
                            range != null &&
                                range.groupValues[1].toLong() == start &&
                                range.groupValues[2].toLong() + 1 ==
                                    range.groupValues[3].toLong() &&
                                (length < 0 || range.groupValues[2].toLong() - start + 1 == length)
                        ) {
                            "Invalid range"
                        }
                    val total =
                        range?.groupValues?.get(3)?.toLong()
                            ?: if (length >= 0) start + length else -1L
                    require(total <= maxBytes)
                    response
                        .header("ETag")
                        ?.takeIf { it.startsWith("\"") && it.length < 512 }
                        ?.let {
                            PersonalArchive.atomicWrite(
                                metadata,
                                Gson().toJson(Resume(identity, it)).toByteArray(),
                            )
                        } ?: metadata.delete()
                    var bytes = start
                    var updateAt = 0L
                    response.body.byteStream().use { input ->
                        java.io.FileOutputStream(target, continued).use { fileOutput ->
                            fileOutput.buffered().use { output ->
                                val buffer = ByteArray(32 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    bytes += n
                                    require(bytes <= maxBytes)
                                    output.write(buffer, 0, n)
                                    val now = android.os.SystemClock.elapsedRealtime()
                                    if (now - updateAt >= 1000) {
                                        progress(bytes, total)
                                        updateAt = now
                                    }
                                }
                                output.flush()
                                fileOutput.fd.sync()
                            }
                        }
                    }
                    check(bytes > 0 && (total < 0 || bytes == total)) { "Incomplete download" }
                    PersonalArchive.atomicWrite(
                        metadata,
                        Gson()
                            .toJson(Resume(identity, response.header("ETag").orEmpty(), bytes))
                            .toByteArray(),
                    )
                    progress(bytes, bytes)
                    target
                }
            } finally {
                cancellation.cancel()
            }
        }

    fun discard(context: Context, id: String) {
        File(context.cacheDir, "media_transfers/$id.part").delete()
        File(context.cacheDir, "media_transfers/$id.json").delete()
        File(context.cacheDir, "hls_transfers/$id").deleteRecursively()
    }
}
