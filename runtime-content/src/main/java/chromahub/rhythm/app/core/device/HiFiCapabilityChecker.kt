/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.core.device

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log

/**
 * Probes whether the device's audio stack and HAL can reliably accept PCM_FLOAT direct output.
 *
 * Two-stage validation:
 *  1. [AudioTrack.getMinBufferSize] — fast buffer size probe without allocation.
 *     Returns ERROR_BAD_VALUE on devices lacking float encoding support.
 *  2. Instantiation of an [AudioTrack] with ENCODING_PCM_FLOAT to verify hardware initialization.
 *
 * The result is cached after the first check since device hardware capability does not change at runtime.
 */
object HiFiCapabilityChecker {
    private const val TAG = "HiFiCapabilityChecker"

    @Volatile
    private var cachedResult: Boolean? = null

    fun isSupported(): Boolean {
        cachedResult?.let { return it }
        return runCheck().also {
            cachedResult = it
            Log.i(TAG, "PCM_FLOAT AudioTrack supported: $it")
        }
    }

    private fun runCheck(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false
        }

        val minBuf = try {
            AudioTrack.getMinBufferSize(
                44_100,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT
            )
        } catch (e: Exception) {
            Log.w(TAG, "getMinBufferSize threw exception for PCM_FLOAT", e)
            return false
        }

        if (minBuf <= 0 || minBuf == AudioTrack.ERROR || minBuf == AudioTrack.ERROR_BAD_VALUE) {
            Log.w(TAG, "getMinBufferSize rejected PCM_FLOAT (result=$minBuf)")
            return false
        }

        return try {
            val track = AudioTrack.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(44_100)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val initialized = track.state == AudioTrack.STATE_INITIALIZED
            track.release()
            if (!initialized) {
                Log.w(TAG, "AudioTrack created with PCM_FLOAT but state was not INITIALIZED")
            }
            initialized
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack PCM_FLOAT creation threw exception", e)
            false
        }
    }
}
