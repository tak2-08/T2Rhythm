// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** QR pixels always use black on white, independent of theme and display contrast settings. */
@Composable
internal fun StreamingInviteQr(invite: StreamingProtocol.Invite) {
    var failed by remember(invite) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, invite) {
        val generated = withContext(Dispatchers.Default) {
            runCatching {
                val matrix = StreamingQr.encode(invite)
                val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            }.getOrNull()
        }
        failed = generated == null
        value = generated
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.streaming_qr_description), Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1f)) }
            ?: if (failed) Text(stringResource(R.string.streaming_qr_error)) else CircularProgressIndicator()
    }
}
