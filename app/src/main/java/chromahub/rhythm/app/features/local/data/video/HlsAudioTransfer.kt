// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import chromahub.rhythm.app.features.local.data.metadata.PersonalArchive
import chromahub.rhythm.app.network.NetworkClient
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/** Resumable sequential HLS parts. fMP4 is remuxed without decoding or re-encoding audio. */
internal object HlsAudioTransfer {
    suspend fun fetch(
        context: Context,
        record: DownloadRecord,
        url: String,
        suffix: String,
        durationMs: Long,
        httpClient: OkHttpClient = NetworkClient.genericHttpClient,
        progress: suspend (Long, Long) -> Unit,
    ): File =
        withContext(Dispatchers.IO) {
            require(suffix in setOf("m4a", "mp4", "mp3") && AudioHlsManifestParser.allowed(url))
            val root = File(context.cacheDir, "hls_transfers/${record.id}").apply { mkdirs() }
            val playlist =
                MediaTransfer.fetch(
                    context,
                    record.copy(id = record.id + "-playlist"),
                    url,
                    "hls:${kr.co.metadata.crawler.CatalogFileStore.hash(url)}",
                    512 * 1024L,
                    httpClient,
                    root,
                ) { _, _ ->
                }
            val manifest = AudioHlsManifestParser.parse(url, playlist.readText())
            if (durationMs > 0)
                require(
                    kotlin.math.abs(manifest.duration - durationMs / 1000.0) <=
                        maxOf(3.0, durationMs / 1000.0 * .05)
                ) {
                    "Incomplete audio stream"
                }
            val finished = File(root, "complete.$suffix")
            val marker = File(root, "completed.txt")
            val expected = manifest.fingerprint
            if (
                finished.length() > 0 &&
                    marker.isFile &&
                    marker.readText() == "$expected:${finished.length()}"
            ) {
                progress(finished.length(), finished.length())
                return@withContext finished
            }
            val parts = mutableListOf<File>()
            var bytes = 0L
            var lastProgressAt = 0L
            for ((index, part) in manifest.parts.withIndex()) {
                currentCoroutineContext().ensureActive()
                val before = bytes
                val file =
                    MediaTransfer.fetch(
                        context,
                        record.copy(id = record.id + "-$index"),
                        part.url,
                        "${expected}:$index",
                        4L * 1024 * 1024,
                        httpClient,
                        root,
                    ) { value, _ ->
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastProgressAt >= 1000) {
                            lastProgressAt = now
                            progress(before + value, -1)
                        }
                    }
                bytes += file.length()
                require(bytes <= AudioDownload.MAX_BYTES)
                parts += file
            }
            require(root.usableSpace > bytes * 2 + 16 * 1024 * 1024) { "Storage is low" }
            val assembled = File(root, "assembled.tmp")
            try {
                assembled.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    for (part in parts) part.inputStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                    output.fd.sync()
                }
                if (suffix in setOf("m4a", "mp4"))
                    YouTubeVideoDownload.installation.withLock { remux(assembled, finished) }
                else {
                    finished.delete()
                    check(assembled.renameTo(finished))
                }
                require(finished.length() in 1..AudioDownload.MAX_BYTES)
                PersonalArchive.atomicWrite(marker, "$expected:${finished.length()}".toByteArray())
                progress(finished.length(), finished.length())
                finished
            } finally {
                assembled.delete()
            }
        }

    private suspend fun remux(source: File, target: File) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        var complete = false
        target.delete()
        try {
            extractor.setDataSource(source.path)
            require(extractor.trackCount == 1)
            val format = extractor.getTrackFormat(0)
            require(format.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm")
            extractor.selectTrack(0)
            muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val track = muxer.addTrack(format)
            muxer.start()
            started = true
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var previous = -1L
            while (true) {
                currentCoroutineContext().ensureActive()
                buffer.clear()
                val count = extractor.readSampleData(buffer, 0)
                if (count < 0) break
                require(count < buffer.capacity() && extractor.sampleTime > previous)
                previous = extractor.sampleTime
                info.set(
                    0,
                    count,
                    previous,
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    else 0,
                )
                muxer.writeSampleData(track, buffer, info)
                extractor.advance()
            }
            require(previous >= 0)
            muxer.stop()
            started = false
            complete = true
        } finally {
            if (started) runCatching { muxer?.stop() }
            muxer?.release()
            extractor.release()
            if (!complete) target.delete()
        }
    }
}
