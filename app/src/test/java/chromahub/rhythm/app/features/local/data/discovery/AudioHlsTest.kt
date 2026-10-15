// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import chromahub.rhythm.app.features.local.data.video.*
import org.junit.Assert.*
import org.junit.Test

class AudioHlsTest {
    private val base =
        "https://playback.media-streaming.soundcloud.cloud/source/audio.m3u8?token=first"

    private fun media(extra: String = "") =
        """#EXTM3U
#EXT-X-VERSION:7
$extra
#EXT-X-MAP:URI="init.mp4?token=first"
#EXTINF:10.000,
audio0.m4s?token=first
#EXTINF:4.038,
audio1.m4s?token=first
#EXT-X-ENDLIST
"""

    @Test
    fun soundCloudCmafAudioRetainsInitializationAndDuration() {
        val parsed = AudioHlsManifestParser.parse(base, media())
        assertEquals(3, parsed.parts.size)
        assertTrue(parsed.parts.first().init)
        assertEquals(14.038, parsed.duration, .00001)
        assertTrue(
            parsed.parts
                .last()
                .url
                .startsWith("https://playback.media-streaming.soundcloud.cloud/source/audio1.m4s")
        )
    }

    @Test
    fun refreshedSignedUrlsKeepContentIdentityWhileChangedPartsDoNot() {
        val old = AudioHlsManifestParser.parse(base, media())
        val refreshed =
            AudioHlsManifestParser.parse(
                base.replace("first", "second"),
                media().replace("first", "second"),
            )
        assertEquals(old.fingerprint, refreshed.fingerprint)
        assertNotEquals(
            old.fingerprint,
            AudioHlsManifestParser.parse(base, media().replace("audio1", "new-audio1")).fingerprint,
        )
    }

    @Test
    fun liveEncryptedRangedVariantAndCrossHostMediaFailClosed() {
        val invalid =
            listOf(
                media().replace("#EXT-X-ENDLIST", ""),
                media("#EXT-X-KEY:METHOD=AES-128,URI=\"key\""),
                media("#EXT-X-BYTERANGE:200@0"),
                media("#EXT-X-STREAM-INF:BANDWIDTH=128000"),
                media().replace("audio0.m4s?token=first", "https://untrusted.example/audio.mp4"),
            )
        invalid.forEach {
            assertTrue(runCatching { AudioHlsManifestParser.parse(base, it) }.isFailure)
        }
        assertFalse(
            AudioHlsManifestParser.allowed("http://playback.media-streaming.soundcloud.cloud/a")
        )
        assertFalse(
            AudioHlsManifestParser.allowed(
                "https://playback.media-streaming.soundcloud.cloud.evil.example/a"
            )
        )
    }

    @Test
    fun invalidDurationsAndUnpairedSegmentsAreNotGuessed() {
        listOf(
                media().replace("10.000", "NaN"),
                media().replace("10.000", "40.0"),
                media().replace("#EXTINF:10.000,", ""),
                "<html>Unavailable</html>",
            )
            .forEach {
                assertTrue(runCatching { AudioHlsManifestParser.parse(base, it) }.isFailure)
            }
    }
}
