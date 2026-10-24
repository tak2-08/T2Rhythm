// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class StreamingQrTest {
    @Test fun generatedQrRoundTripsTheExactPinnedInvitationAndExpiresNormally() {
        val now = System.currentTimeMillis()
        val invite = StreamingProtocol.Invite(UUID.randomUUID().toString(), "음악 공유 폰", "https://192.168.1.3:8898", "a".repeat(64), StreamingProtocol.token(), now + 120_000, binding = 2)
        val matrix = StreamingQr.encode(invite)
        val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt() }
        val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels))))
        assertEquals(invite, StreamingProtocol.Invite.decode(result.text, now))
        assertThrows(IllegalArgumentException::class.java) { StreamingProtocol.Invite.decode(result.text, invite.expires) }
        assertFalse(result.text.contains("Bearer"))
    }
}
