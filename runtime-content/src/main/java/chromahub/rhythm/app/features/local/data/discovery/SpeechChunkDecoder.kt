// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.content.Context
import android.media.*
import chromahub.rhythm.app.features.local.data.experience.AnalysisBudget
import chromahub.rhythm.app.shared.data.model.Song
import java.nio.ByteOrder
import kotlin.math.*

internal fun interface PcmSink {
    fun accept(value: Double, rate: Int)
}

/** Bounded PCM windows; no whole-song buffers and no microphone capture. */
internal object SpeechChunkDecoder {
    fun decode(
        context: Context,
        song: Song,
        startMs: Long,
        endMs: Long,
        budget: AnalysisBudget,
        sink: PcmSink,
    ) {
        require(startMs >= 0 && endMs > startMs && endMs - startMs <= 15_000)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, song.uri, null)
            val track =
                (0 until extractor.trackCount).first {
                    extractor
                        .getTrackFormat(it)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                }
            extractor.selectTrack(track)
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            fun consume(buffer: java.nio.ByteBuffer, timeUs: Long) {
                require(rate in 8000..384000 && channels in 1..32)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                val frameBytes = channels * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                var frame = 0L
                while (buffer.remaining() >= frameBytes) {
                    val at = timeUs + frame * 1_000_000 / rate
                    var sample = 0.0
                    repeat(channels) {
                        sample +=
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) buffer.float.toDouble()
                            else buffer.short / 32768.0
                    }
                    if (at >= startMs * 1000 && at < endMs * 1000)
                        sink.accept(
                            (sample / channels).takeIf(Double::isFinite)?.coerceIn(-1.0, 1.0)
                                ?: 0.0,
                            rate,
                        )
                    if (++frame % 512 == 0L) budget.blockingCheckpoint()
                }
            }
            if (mime == "audio/raw") {
                encoding =
                    if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    else AudioFormat.ENCODING_PCM_16BIT
                require(
                    encoding in
                        setOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)
                )
                val buffer = java.nio.ByteBuffer.allocate(256 * 1024)
                while (extractor.sampleTime in 0 until endMs * 1000) {
                    budget.blockingCheckpoint()
                    buffer.clear()
                    val n = extractor.readSampleData(buffer, 0)
                    if (n < 0) break
                    buffer.position(0)
                    buffer.limit(n)
                    consume(buffer, extractor.sampleTime)
                    extractor.advance()
                }
                return
            }
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var inputEnded = false
            var outputEnded = false
            val info = MediaCodec.BufferInfo()
            while (!outputEnded) {
                budget.blockingCheckpoint()
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val time = extractor.sampleTime
                        val n =
                            if (time < 0 || time >= endMs * 1000) -1
                            else extractor.readSampleData(buffer, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(
                                index,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(index, 0, n, time, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = decoder.outputFormat
                    rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding =
                        if (out.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        else AudioFormat.ENCODING_PCM_16BIT
                    require(
                        encoding in
                            setOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)
                    )
                } else if (index >= 0) {
                    try {
                        val buffer = decoder.getOutputBuffer(index)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        consume(buffer, info.presentationTimeUs)
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally {
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }
}

/** Polyphase low-pass resampler: bounded 32-sample ring, no per-sample allocations. */
internal class SpeechResampler(private val output: (ShortArray, Int) -> Unit) : PcmSink {
    private val ring = DoubleArray(32)
    private val buffer = ShortArray(2048)
    private var used = 0
    private var sourceRate = 0
    private var inputIndex = -1L
    private var next = 0.0
    private var coefficients = emptyArray<DoubleArray>()

    override fun accept(value: Double, rate: Int) {
        if (rate != sourceRate) {
            sourceRate = rate
            inputIndex = -1
            next = 0.0
            ring.fill(0.0)
            val cutoff = minOf(.45, 7200.0 / rate)
            coefficients =
                Array(64) { phase ->
                    val weights =
                        DoubleArray(32) { tap ->
                            val x = tap - 15 - phase / 64.0
                            val sinc =
                                if (abs(x) < 1e-10) 2 * cutoff
                                else sin(2 * PI * cutoff * x) / (PI * x)
                            sinc * (.5 - .5 * cos(2 * PI * tap / 31))
                        }
                    val sum = weights.sum()
                    weights.map { it / sum }.toDoubleArray()
                }
        }
        inputIndex++
        ring[(inputIndex % 32).toInt()] = value
        while (inputIndex >= floor(next).toLong() + 16) {
            val base = floor(next).toLong()
            val phase = ((next - base) * 64).toInt().coerceIn(0, 63)
            var sample = 0.0
            repeat(32) { tap ->
                val at = base + tap - 15
                if (at >= 0 && inputIndex - at < 32)
                    sample += ring[(at % 32).toInt()] * coefficients[phase][tap]
            }
            buffer[used++] = (sample.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
            if (used == buffer.size) flush()
            next += sourceRate / 16000.0
        }
    }

    fun flush() {
        if (used > 0) {
            output(buffer, used)
            used = 0
        }
    }
}
