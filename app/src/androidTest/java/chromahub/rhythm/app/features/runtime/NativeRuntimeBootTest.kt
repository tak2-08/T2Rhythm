// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.runtime

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import chromahub.rhythm.app.activities.MainActivity
import chromahub.rhythm.app.features.local.presentation.components.settings.LanguageHelper
import chromahub.rhythm.app.features.updates.UpdateMaintenanceWorker
import chromahub.rhythm.app.infrastructure.service.MediaPlaybackService
import chromahub.rhythm.app.infrastructure.widget.glance.RhythmWidgetReceiver
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.runtime.api.RuntimeComponentRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.io.File
import java.util.concurrent.TimeUnit

/** Full music implementation, installed fixed components, actual playback and persisted jobs. */
class NativeRuntimeBootTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun grantPermission(permission: String) {
        if (Build.VERSION.SDK_INT >= 28) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        else android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission")
        ).use { it.readBytes() }
    }
    private val preparation = object : ExternalResource() {
        override fun before() {
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            grantPermission(permission)
            if (Build.VERSION.SDK_INT >= 33) grantPermission(Manifest.permission.POST_NOTIFICATIONS)
            AppSettings.getInstance(context).setOnboardingCompleted(true)
            context.getSharedPreferences("installed_release_notes", Context.MODE_PRIVATE).edit()
                .putInt("seen", chromahub.rhythm.app.BuildConfig.VERSION_CODE).commit()
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(preparation).around(compose)

    @Test fun realMusicUiAndWidgetsUseFeatureClassesWhileAndroidComponentsStayInBase() {
        val host = RuntimeComponentRegistry.host()
        val feature = host.feature("test")
        val status = host.updates().status()
        assertFalse(feature.describe().getBoolean("failed"))
        assertEquals(status.getLong("versionCode"), feature.describe().getLong("compiledVersionCode"))
        assertNotSame(MainActivity::class.java.classLoader, feature.javaClass.classLoader)
        if (!chromahub.rhythm.app.BuildConfig.DEBUG) {
            val baseSdk = MainActivity::class.java.classLoader!!.loadClass("chromahub.rhythm.runtime.base.DesugaredSdkContract")
            assertNotNull(baseSdk.getMethod("toUnmodifiableList").invoke(null))
        }
        assertSame(feature.javaClass.classLoader, AppSettings::class.java.classLoader)
        assertSame(MainActivity::class.java, feature.javaClass.classLoader!!.loadClass(MainActivity::class.java.name))
        assertSame(feature.javaClass.classLoader, RhythmWidgetReceiver().glanceAppWidget.javaClass.classLoader)
        compose.waitUntil(90_000) {
            var initialized = false
            compose.runOnUiThread {
                val model = androidx.lifecycle.ViewModelProvider(compose.activity)[chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel::class.java]
                initialized = model.isInitialized.value
            }
            initialized
        }
        compose.onRoot().assertExists()
        compose.waitForIdle()
        val screenshots = File(context.getExternalFilesDir(null), "native-music-api${Build.VERSION.SDK_INT}.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            if (bitmap != null) { screenshots.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        }
        assertEquals(context.packageName, context.packageManager.getPackageInfo(context.packageName, 0).packageName)
    }

    @Test fun installedPlaybackServicePlaysALocalFileWithTheChildOwnedPlayerLogic() {
        val fixture = File(context.cacheDir, "native-service-fixture.m4a")
        instrumentation.context.assets.open("library-fixture.m4a").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        ContextCompat.startForegroundService(context, Intent(context, MediaPlaybackService::class.java).setAction(MediaPlaybackService.ACTION_INIT_SERVICE))
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        instrumentation.runOnMainSync {
            future = MediaController.Builder(context, SessionToken(context, ComponentName(context, MediaPlaybackService::class.java))).buildAsync()
        }
        val controller = requireNotNull(future).get(30, TimeUnit.SECONDS)
        try {
            instrumentation.runOnMainSync {
                controller.setMediaItem(MediaItem.Builder().setMediaId("native-local-fixture").setUri(Uri.fromFile(fixture)).build())
                controller.prepare(); controller.seekTo(10_000); controller.play()
            }
            val deadline = SystemClock.elapsedRealtime() + 30_000
            var playing = false
            while (!playing && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { playing = controller.isPlaying && controller.currentPosition >= 10_000 }
                if (!playing) Thread.sleep(100)
            }
            assertTrue("The real installed service must play the local fixture", playing)
            var start = 0L
            instrumentation.runOnMainSync { start = controller.currentPosition }
            Thread.sleep(1800)
            instrumentation.runOnMainSync {
                assertTrue("Local playback must advance without rewinding", controller.currentPosition >= start + 800)
                assertEquals("native-local-fixture", controller.currentMediaItem?.mediaId)
            }
        } finally {
            instrumentation.runOnMainSync { controller.stop(); controller.clearMediaItems(); controller.release() }
            context.stopService(Intent(context, MediaPlaybackService::class.java)); fixture.delete()
        }
    }

    @Test fun persistedWorkManagerClassNamesResolveThroughTheFeatureFactory() {
        val work = OneTimeWorkRequestBuilder<UpdateMaintenanceWorker>().build()
        val manager = WorkManager.getInstance(context)
        manager.enqueue(work).result.get(15, TimeUnit.SECONDS)
        val deadline = SystemClock.elapsedRealtime() + 30_000
        var state: WorkInfo.State? = null
        do {
            state = manager.getWorkInfoById(work.id).get(5, TimeUnit.SECONDS)?.state
            if (state?.isFinished == true) break
            Thread.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        assertEquals(WorkInfo.State.SUCCEEDED, state)
    }
}
