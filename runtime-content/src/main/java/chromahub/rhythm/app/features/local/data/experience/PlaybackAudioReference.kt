// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Foreground opt-in only. Decoded playback PCM is never persisted or exposed to the network. */
internal object PlaybackAudioReference {
    @Volatile var collecting = false
        private set
    private val ring = FloatArray(16_000 * 4)
    private var written = 0L
    private var lastWrite = 0L
    private var phase = 0.0
    private var sum = 0.0
    private var frames = 0

    @Synchronized fun enable(value: Boolean) {
        if (collecting == value) return
        collecting = value
        clear()
    }
    @Synchronized fun clear() {
        ring.fill(0f); written = 0; lastWrite = 0; phase = 0.0; sum = 0.0; frames = 0
    }
    @Synchronized fun append(buffer: ByteBuffer, sampleRate: Int, channels: Int, encoding: Int) {
        if (!collecting || sampleRate !in 8_000..384_000 || channels !in 1..32) return
        val bytes = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val big = encoding in setOf(C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT_BIG_ENDIAN)
        val input = buffer.duplicate().order(if (big) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        while (input.remaining() >= bytes * channels) {
            var mono = 0.0
            repeat(channels) {
                mono += when (bytes) {
                    1 -> ((input.get().toInt() and 255) - 128) / 128.0
                    2 -> input.short / 32768.0
                    3 -> {
                        val a = input.get().toInt() and 255; val b = input.get().toInt() and 255; val c = input.get().toInt() and 255
                        val value = if (big) (a shl 16) or (b shl 8) or c else a or (b shl 8) or (c shl 16)
                        (value shl 8 shr 8) / 8388608.0
                    }
                    else -> if (encoding == C.ENCODING_PCM_FLOAT) input.float.toDouble() else input.int / 2147483648.0
                }
            }
            sum += (mono / channels).takeIf(Double::isFinite) ?: 0.0; frames++
            phase += 16_000.0 / sampleRate
            if (phase >= 1.0) {
                val sample = (sum / frames).toFloat()
                while (phase >= 1.0) { ring[(written++ % ring.size).toInt()] = sample; phase -= 1.0 }
                sum = 0.0; frames = 0
            }
        }
        lastWrite = System.nanoTime()
    }
    @Synchronized fun snapshot(): FloatArray {
        if (!collecting || System.nanoTime() - lastWrite > 2_000_000_000L) return FloatArray(0)
        val count = minOf(written, ring.size.toLong()).toInt()
        return FloatArray(count) { ring[((written - count + it) % ring.size).toInt()] }
    }
}
