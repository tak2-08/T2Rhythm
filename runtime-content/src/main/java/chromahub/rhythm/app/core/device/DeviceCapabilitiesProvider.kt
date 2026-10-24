/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.core.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import java.util.Locale

enum class AudioOutputCategory {
    BuiltIn,
    Bluetooth,
    Usb,
    Wired,
    Cast,
    Other
}

data class OutputRouteInfo(
    val id: Int,
    val name: String,
    val type: Int,
    val category: AudioOutputCategory
)

data class AudioCodecInfo(
    val name: String,
    val mimeType: String,
    val isHardwareAccelerated: Boolean,
    val maxInstances: Int
)

data class DeviceAudioCapabilities(
    val nativeSampleRate: Int,
    val framesPerBuffer: Int,
    val isLowLatencySupported: Boolean,
    val isProAudioSupported: Boolean,
    val isPcmFloatSupported: Boolean,
    val offloadSupportedFormats: List<String>,
    val outputRoutes: List<OutputRouteInfo>,
    val allDecoders: List<AudioCodecInfo>
)

data class DeviceSystemSummary(
    val manufacturer: String,
    val model: String,
    val brand: String,
    val hardware: String,
    val androidVersion: String,
    val sdkInt: Int,
    val availableRamBytes: Long,
    val totalRamBytes: Long
)

object DeviceCapabilitiesProvider {

    fun getCapabilities(context: Context): DeviceAudioCapabilities {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 44_100
        val framesPerBuffer = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256

        val packageManager = context.packageManager
        val hasLowLatency = packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)
        val hasProAudio = packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)
        val pcmFloat = HiFiCapabilityChecker.isSupported()

        val offloadFormats = getOffloadSupportedFormats()
        val decoders = getAudioDecoders()
        val routes = getOutputRoutes(audioManager)

        return DeviceAudioCapabilities(
            nativeSampleRate = sampleRate,
            framesPerBuffer = framesPerBuffer,
            isLowLatencySupported = hasLowLatency,
            isProAudioSupported = hasProAudio,
            isPcmFloatSupported = pcmFloat,
            offloadSupportedFormats = offloadFormats,
            outputRoutes = routes,
            allDecoders = decoders
        )
    }

    fun getSystemSummary(context: Context): DeviceSystemSummary {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)

        return DeviceSystemSummary(
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            model = Build.MODEL,
            brand = Build.BRAND.replaceFirstChar { it.uppercase() },
            hardware = Build.HARDWARE,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            availableRamBytes = memInfo.availMem,
            totalRamBytes = memInfo.totalMem
        )
    }

    fun isLikelyHardwareDecoder(codecName: String, codecInfo: MediaCodecInfo? = null): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && codecInfo != null) {
            val isSamsung = Build.MANUFACTURER.lowercase(Locale.US) == "samsung"
            if (isSamsung && codecName.startsWith("c2.sec.")) return true
            return codecInfo.isHardwareAccelerated
        }
        val normalized = codecName.lowercase(Locale.US)
        val knownSoftwareTokens = listOf(
            "omx.google.",
            "c2.android.",
            "ffmpeg",
            "midi",
            "jsyn",
            "libgav1",
            "dav1d"
        )
        if (knownSoftwareTokens.any { normalized.contains(it) }) return false

        return normalized.startsWith("omx.") ||
            normalized.startsWith("c2.") ||
            normalized.contains(".qti.") ||
            normalized.contains(".qcom.") ||
            normalized.contains(".sec.") ||
            normalized.contains(".mtk.") ||
            normalized.contains(".exynos.") ||
            normalized.contains(".dolby.")
    }

    fun getAudioDecoders(): List<AudioCodecInfo> {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val decoders = mutableListOf<AudioCodecInfo>()

        for (info in codecList.codecInfos) {
            if (info.isEncoder) continue
            val types = info.supportedTypes.filter { it.startsWith("audio/") }
            if (types.isEmpty()) continue

            val isHw = isLikelyHardwareDecoder(info.name, info)

            for (type in types) {
                val maxInstances = try {
                    info.getCapabilitiesForType(type).maxSupportedInstances
                } catch (_: Exception) {
                    -1
                }
                decoders.add(
                    AudioCodecInfo(
                        name = info.name,
                        mimeType = type,
                        isHardwareAccelerated = isHw,
                        maxInstances = maxInstances
                    )
                )
            }
        }
        return decoders.sortedWith(compareBy({ it.mimeType }, { !it.isHardwareAccelerated }, { it.name }))
    }

    fun getOutputRoutes(audioManager: AudioManager): List<OutputRouteInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return emptyList()
        }
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { device ->
                val category = device.type.toAudioOutputCategory()
                val name = device.productName?.toString()?.takeIf { it.isNotBlank() }
                    ?: category.name
                OutputRouteInfo(
                    id = device.id,
                    name = name,
                    type = device.type,
                    category = category
                )
            }
            .distinctBy { it.category to it.name.lowercase(Locale.US) }
            .sortedBy { it.category.ordinal }
    }

    fun getOffloadSupportedFormats(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val candidates = listOf(
            "MP3" to AudioFormat.ENCODING_MP3,
            "AAC" to AudioFormat.ENCODING_AAC_LC,
            "WAV" to AudioFormat.ENCODING_PCM_16BIT
        ) + (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) listOf("Opus" to AudioFormat.ENCODING_OPUS) else emptyList())

        return candidates.mapNotNull { (label, encoding) ->
            val isSupported = runCatching {
                val format = AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(44_100)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
                AudioManager.isOffloadedPlaybackSupported(format, attributes)
            }.getOrDefault(false)

            if (isSupported) label else null
        }
    }

    fun generateDiagnosticReport(context: Context): String {
        val caps = getCapabilities(context)
        val sys = getSystemSummary(context)

        return buildString {
            appendLine("=== Rhythm Audio & Device Diagnostics ===")
            appendLine("Device: ${sys.brand} ${sys.model} (${sys.hardware})")
            appendLine("Android OS: ${sys.androidVersion} (API ${sys.sdkInt})")
            appendLine("RAM: ${Formatter.formatShortFileSize(context, sys.availableRamBytes)} free of ${Formatter.formatShortFileSize(context, sys.totalRamBytes)}")
            appendLine()
            appendLine("--- Audio Stack ---")
            appendLine("Native HAL Sample Rate: ${caps.nativeSampleRate} Hz")
            appendLine("HAL Buffer Frames: ${caps.framesPerBuffer}")
            appendLine("Low Latency Feature: ${if (caps.isLowLatencySupported) "Supported" else "Not Available"}")
            appendLine("Pro Audio Feature: ${if (caps.isProAudioSupported) "Supported" else "Not Available"}")
            appendLine("Hi-Fi PCM_FLOAT Direct Output: ${if (caps.isPcmFloatSupported) "Supported" else "Unsupported (16-bit integer HAL)"}")
            appendLine("DSP Offload Supported Formats: ${if (caps.offloadSupportedFormats.isEmpty()) "None" else caps.offloadSupportedFormats.joinToString(", ")}")
            appendLine()
            appendLine("--- Audio Output Devices ---")
            if (caps.outputRoutes.isEmpty()) {
                appendLine("Default Output Route")
            } else {
                caps.outputRoutes.forEach { route ->
                    appendLine("• ${route.name} [${route.category}]")
                }
            }
            appendLine()
            appendLine("--- Detected Audio Decoders ---")
            caps.allDecoders.groupBy { it.mimeType }.forEach { (mime, list) ->
                appendLine("Format: $mime")
                list.forEach { dec ->
                    val hwTag = if (dec.isHardwareAccelerated) "HW" else "SW"
                    appendLine("  • [${hwTag}] ${dec.name}")
                }
            }
        }
    }

    private fun Int.toAudioOutputCategory(): AudioOutputCategory {
        return when (this) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioOutputCategory.BuiltIn
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> AudioOutputCategory.Bluetooth
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET -> AudioOutputCategory.Usb
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> AudioOutputCategory.Wired
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC -> AudioOutputCategory.Cast
            else -> AudioOutputCategory.Other
        }
    }
}
