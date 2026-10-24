// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object StreamingQr {
    fun encode(invite: StreamingProtocol.Invite, size: Int = 768): BitMatrix {
        require(size in 256..1200)
        return QRCodeWriter().encode(invite.encode(), BarcodeFormat.QR_CODE, size, size, mapOf(
            EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ))
    }
}
