package chromahub.rhythm.app.features.local.data.experience

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PlaybackAudioReferenceTest {
    @Test fun downsamplingNeverConsumesOrChangesThePlayersBuffer() {
        val buffer = ByteBuffer.allocate(2400 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(2400) { buffer.putShort(1000); buffer.putShort(3000) }
        buffer.flip()
        val original = buffer.array().copyOf()
        try {
            PlaybackAudioReference.enable(true)
            PlaybackAudioReference.append(buffer, 48000, 2, C.ENCODING_PCM_16BIT)
            assertEquals(0, buffer.position())
            assertArrayEquals(original, buffer.array())
            val samples = PlaybackAudioReference.snapshot()
            assertEquals(800, samples.size)
            assertTrue(samples.all { kotlin.math.abs(it - 2000f / 32768f) < 1e-6 })
        } finally { PlaybackAudioReference.enable(false) }
        assertEquals(0, PlaybackAudioReference.snapshot().size)
    }
    @Test fun floatAndBigEndianPcmAreSupportedAndEncodedDataIsIgnored() {
        try {
            PlaybackAudioReference.enable(true)
            val floats = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(.25f).putFloat(-.5f).apply { flip() }
            PlaybackAudioReference.append(floats, 16000, 1, C.ENCODING_PCM_FLOAT)
            assertArrayEquals(floatArrayOf(.25f, -.5f), PlaybackAudioReference.snapshot(), 1e-6f)
            PlaybackAudioReference.clear()
            val big = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putShort(8192).putShort(-16384).apply { flip() }
            PlaybackAudioReference.append(big, 16000, 1, C.ENCODING_PCM_16BIT_BIG_ENDIAN)
            assertArrayEquals(floatArrayOf(.25f, -.5f), PlaybackAudioReference.snapshot(), 1e-6f)
            PlaybackAudioReference.clear()
            PlaybackAudioReference.append(big, 16000, 1, C.ENCODING_INVALID)
            assertEquals(0, PlaybackAudioReference.snapshot().size)
        } finally { PlaybackAudioReference.enable(false) }
    }
}
