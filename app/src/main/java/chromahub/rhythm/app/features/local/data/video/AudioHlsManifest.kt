// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.video

import java.net.URI
import kr.co.metadata.crawler.CatalogFileStore

internal data class AudioHlsPart(val url: String, val duration: Double, val init: Boolean = false)

internal data class AudioHlsManifest(val parts: List<AudioHlsPart>, val duration: Double) {
    val fingerprint: String
        get() =
            CatalogFileStore.hash(
                parts.joinToString("\n") {
                    val uri = URI(it.url)
                    "${uri.host}${uri.rawPath}:${it.duration}:${it.init}"
                }
            )
}

/**
 * Public VOD audio only. Unsupported encryption, live, ranges and variant playlists fail closed.
 */
internal object AudioHlsManifestParser {
    fun allowed(url: String): Boolean =
        runCatching {
                val uri = URI(url)
                val host = uri.host?.lowercase().orEmpty()
                uri.scheme == "https" &&
                    uri.userInfo == null &&
                    uri.port in setOf(-1, 443) &&
                    (host.endsWith(".sndcdn.com") ||
                        host.endsWith(".soundcloud.com") ||
                        host.endsWith(".soundcloud.cloud"))
            }
            .getOrDefault(false)

    fun parse(base: String, text: String): AudioHlsManifest {
        require(allowed(base) && text.length <= 512 * 1024)
        val rows =
            text
                .removePrefix("\uFEFF")
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        require(rows.firstOrNull() == "#EXTM3U" && rows.lastOrNull() == "#EXT-X-ENDLIST")
        val parts = mutableListOf<AudioHlsPart>()
        var duration: Double? = null
        var total = 0.0
        fun resolve(value: String): String =
            URI(base).resolve(value).toString().also { require(allowed(it)) }
        for (row in rows.drop(1)) {
            when {
                row.startsWith("#EXT-X-KEY:") -> require(row.substringAfter(':') == "METHOD=NONE")
                row.startsWith("#EXT-X-STREAM-INF") ||
                    row.startsWith("#EXT-X-I-FRAME") ||
                    row.startsWith("#EXT-X-BYTERANGE") ||
                    row.startsWith("#EXT-X-DISCONTINUITY") ||
                    row.startsWith("#EXT-X-GAP") -> error("Unsupported HLS audio")
                row.startsWith("#EXT-X-MAP:") -> {
                    require(parts.isEmpty() && !row.contains("BYTERANGE"))
                    val url =
                        Regex("URI=\"([^\"]+)\"").find(row)?.groupValues?.get(1)
                            ?: error("Missing initialization segment")
                    parts += AudioHlsPart(resolve(url), 0.0, true)
                }
                row.startsWith("#EXTINF:") -> {
                    require(duration == null)
                    duration =
                        row.substringAfter(':').substringBefore(',').toDouble().also {
                            require(it.isFinite() && it in .001..30.0)
                        }
                }
                !row.startsWith('#') -> {
                    val length = duration ?: error("Segment duration missing")
                    duration = null
                    total += length
                    require(total <= 3601 && parts.size < 1001)
                    parts += AudioHlsPart(resolve(row), length)
                }
            }
        }
        require(duration == null && parts.any { !it.init } && total >= 1)
        return AudioHlsManifest(parts, total)
    }
}
