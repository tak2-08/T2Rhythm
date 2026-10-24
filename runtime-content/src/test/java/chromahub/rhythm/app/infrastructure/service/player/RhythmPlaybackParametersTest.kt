/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.infrastructure.service.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

class RhythmPlaybackParametersTest {

    @Test
    fun playbackParameters_clampedCorrectly() {
        val minSpeed = 0.1f.coerceIn(0.25f, 3.0f)
        val maxSpeed = 5.0f.coerceIn(0.25f, 3.0f)
        val normalSpeed = 1.25f.coerceIn(0.25f, 3.0f)

        assertEquals(0.25f, minSpeed, 0.001f)
        assertEquals(3.0f, maxSpeed, 0.001f)
        assertEquals(1.25f, normalSpeed, 0.001f)

        val minPitch = 0.2f.coerceIn(0.5f, 2.0f)
        val maxPitch = 3.0f.coerceIn(0.5f, 2.0f)
        val normalPitch = 0.9f.coerceIn(0.5f, 2.0f)

        assertEquals(0.5f, minPitch, 0.001f)
        assertEquals(2.0f, maxPitch, 0.001f)
        assertEquals(0.9f, normalPitch, 0.001f)
    }

    @Test
    fun floatOutputDecision_inhibitedWhenPlaybackRateModified() {
        fun shouldEnableFloatOutput(
            prefEnabled: Boolean,
            hwSupported: Boolean,
            speed: Float,
            pitch: Float,
            hasEffects: Boolean,
            skipSilence: Boolean
        ): Boolean {
            val isPlaybackRateModified = abs(speed - 1f) > 0.001f || abs(pitch - 1f) > 0.001f
            val hasActiveProcessors = hasEffects || skipSilence || isPlaybackRateModified
            return prefEnabled && hwSupported && !hasActiveProcessors
        }

        // Standard default: float output pref is false -> disabled
        assertFalse(shouldEnableFloatOutput(false, true, 1.0f, 1.0f, false, false))

        // Explicitly enabled by user on supported HW with default speed/pitch -> enabled
        assertTrue(shouldEnableFloatOutput(true, true, 1.0f, 1.0f, false, false))

        // Inhibited when speed is modified (e.g. 1.25x) to allow Sonic time-stretching
        assertFalse(shouldEnableFloatOutput(true, true, 1.25f, 1.0f, false, false))

        // Inhibited when pitch is modified (e.g. 0.9x)
        assertFalse(shouldEnableFloatOutput(true, true, 1.0f, 0.9f, false, false))

        // Inhibited when DSP effects are active
        assertFalse(shouldEnableFloatOutput(true, true, 1.0f, 1.0f, true, false))

        // Inhibited when silence skipping is active
        assertFalse(shouldEnableFloatOutput(true, true, 1.0f, 1.0f, false, true))

        // Inhibited when hardware does not support float output
        assertFalse(shouldEnableFloatOutput(true, false, 1.0f, 1.0f, false, false))
    }

    @Test
    fun sonicAudioProcessor_rejectsHighResIntegerPcmWithoutDownsampling() {
        val sonic = SonicAudioProcessor()
        sonic.setSpeed(1.25f)
        sonic.setPitch(1.0f)

        // 16-bit integer PCM must configure successfully
        val format16Bit = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        val outputFormat = sonic.configure(format16Bit)
        assertEquals(C.ENCODING_PCM_16BIT, outputFormat.encoding)
        assertTrue(sonic.isActive)

        // 24-bit high-res PCM (standard for high-res FLAC) must be rejected because Sonic does not support 24-bit
        val format24Bit = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_24BIT)
        try {
            sonic.configure(format24Bit)
            fail("SonicAudioProcessor should reject ENCODING_PCM_24BIT")
        } catch (e: AudioProcessor.UnhandledAudioFormatException) {
            // Expected: confirms that high-res PCM must be converted to 16-bit PCM for Sonic
            assertEquals(C.ENCODING_PCM_24BIT, e.inputAudioFormat.encoding)
        }
    }
}
