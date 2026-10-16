// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.util.ArtworkInvalidation
import chromahub.rhythm.app.util.M3ImageUtils
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ArtworkRefreshTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun mountedArtworkReloadsAfterEditingSameUriWithoutReopeningScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "artwork-refresh-${UUID.randomUUID()}.png")
        fun paint(color: Int) {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
        }
        try {
            paint(Color.RED)
            val at = file.lastModified()
            val uri = Uri.fromFile(file)
            compose.setContent {
                MaterialTheme {
                    M3ImageUtils.M3MediaImage(
                        data = uri,
                        contentDescription = "Artwork",
                        modifier = Modifier.size(96.dp).testTag("artwork"),
                    )
                }
            }
            fun dominant(red: Boolean): Boolean {
                val image = compose.onNodeWithTag("artwork").captureToImage().toPixelMap()
                val pixel = image[image.width / 2, image.height / 2]
                return if (red) pixel.red > .8f && pixel.blue < .2f
                else pixel.blue > .8f && pixel.red < .2f
            }
            compose.waitUntil(45_000) { dominant(true) }
            paint(Color.BLUE)
            assertTrue(file.setLastModified(at))
            runBlocking { ArtworkInvalidation.invalidate(context) }
            compose.waitUntil(45_000) { dominant(false) }
            assertEquals(uri, Uri.fromFile(file))
        } finally {
            file.delete()
        }
    }
}
