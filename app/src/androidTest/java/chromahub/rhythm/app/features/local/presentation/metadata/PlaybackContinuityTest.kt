// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.Manifest
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.AppSettings
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.*
import org.junit.Assert.*

class PlaybackContinuityTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>(effectContext = StandardTestDispatcher())
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val settings get() = AppSettings.getInstance(context)
    private var uri: android.net.Uri? = null
    private var legacyFile: java.io.File? = null
    private var vm: MusicViewModel? = null
    private val controllerField = MusicViewModel::class.java.getDeclaredField("mediaController").apply { isAccessible = true }
    private fun controller() = controllerField.get(requireNotNull(vm)) as? MediaController

    @After fun cleanup() {
        chromahub.rhythm.app.features.local.data.experience.PlaybackAudioReference.enable(false)
        compose.runOnUiThread { if (vm != null) controller()?.let { it.stop(); it.clearMediaItems() } }
        uri?.let { context.contentResolver.delete(it, null, null) }
        legacyFile?.delete()
    }

    @Test fun liveLocalPlaybackIsNeverRewoundByEitherSavedQueueRestorePath() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        chromahub.rhythm.app.testing.grantTestPermission(permission)
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < 29) {
            chromahub.rhythm.app.testing.grantTestPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val directory = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "T2Continuity").apply { mkdirs() }
            val file = java.io.File(directory, "continuity-${System.nanoTime()}.m4a"); legacyFile = file
            instrumentation.context.assets.open("library-fixture.m4a").use { input -> file.outputStream().use { input.copyTo(it) } }
            val scanned = java.util.concurrent.CountDownLatch(1)
            android.media.MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("audio/mp4")) { _, value -> uri = value; scanned.countDown() }
            assertTrue(scanned.await(30, java.util.concurrent.TimeUnit.SECONDS))
            assertNotNull(uri)
        } else {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "continuity-${System.nanoTime()}.m4a")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/T2Continuity/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
        instrumentation.context.assets.open("library-fixture.m4a").use { input -> context.contentResolver.openOutputStream(uri!!)!!.use { input.copyTo(it) } }
        context.contentResolver.update(uri!!, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        var duration = 0L
        while (duration == 0L && android.os.SystemClock.elapsedRealtime() < deadline) {
            duration = context.contentResolver.query(uri!!, arrayOf(MediaStore.Audio.Media.DURATION), null, null, null)?.use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            } ?: 0L
            if (duration == 0L) Thread.sleep(100)
        }
        assertTrue("Real audio fixture must be indexed before library initialization", duration > 30_000)
        compose.setContent { vm = androidx.lifecycle.viewmodel.compose.viewModel(); MaterialTheme {} }
        compose.waitUntil(90_000) {
            var ready = false
            compose.runOnUiThread { ready = vm?.isInitialized?.value == true && controller() != null && vm!!.songs.value.any { it.uri == uri } }
            ready
        }
        val song = vm!!.songs.value.single { it.uri == uri }
        val mediaItem = androidx.media3.common.MediaItem.Builder().setMediaId(song.id).setUri(song.uri).build()
        compose.runOnUiThread {
            controller()!!.apply { setMediaItem(mediaItem); prepare(); seekTo(20_000); play() }
        }
        compose.waitUntil(30_000) {
            var playing = false
            compose.runOnUiThread { playing = controller()!!.isPlaying && controller()!!.currentPosition >= 20_000 }
            playing
        }
        // Exercise the real decoder/sink tap while checking that queue restoration cannot rewind audio.
        val reference = chromahub.rhythm.app.features.local.data.experience.PlaybackAudioReference
        reference.enable(true)
        compose.waitUntil(10_000) { reference.snapshot().size >= 16_000 }
        assertTrue("The installed player must supply actual non-silent decoded PCM", reference.snapshot().any { kotlin.math.abs(it) > .001f })
        val backwards = mutableListOf<Long>()
        val listener = object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (newPosition.positionMs < oldPosition.positionMs - 500) backwards += newPosition.positionMs
            }
        }
        compose.runOnUiThread {
            controller()!!.addListener(listener)
            settings.setSavedPlaybackPosition(1_000)
            val pending = MusicViewModel::class.java.getDeclaredField("pendingQueueRestore").apply { isAccessible = true }
            pending.set(vm, listOf(song.id) to 0)
            MusicViewModel::class.java.getDeclaredMethod("restoreQueueAfterControllerReady").apply { isAccessible = true }.invoke(vm)
            MusicViewModel::class.java.getDeclaredMethod("restoreSavedQueue", List::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(vm, listOf(song.id), 0)
        }
        Thread.sleep(3_000)
        compose.runOnUiThread {
            val helper = MusicViewModel::class.java.getDeclaredField("metadataManagerHelper").apply { isAccessible = true }.get(vm)
            @Suppress("UNCHECKED_CAST")
            val resume = helper.javaClass.getDeclaredField("resumePlaybackAfterWrite").apply { isAccessible = true }.get(helper)
                as (String, Pair<Long, Boolean>?) -> Unit
            // A slow edit of a different song must not seek the song that is now playing.
            resume("different-${song.id}", 1_000L to true)
        }
        Thread.sleep(500)
        compose.runOnUiThread {
            assertEquals(song.id, controller()!!.currentMediaItem!!.mediaId)
            assertTrue("An old 1-second snapshot must not replace live playback", controller()!!.currentPosition >= 20_000)
            assertTrue("Unexpected backwards seek: $backwards", backwards.isEmpty())
            controller()!!.removeListener(listener)
        }
    }
}
