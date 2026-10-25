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
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.activities.MainActivity
import chromahub.rhythm.app.features.local.data.database.RhythmDatabase
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.infrastructure.service.MediaPlaybackService
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.runtime.api.RuntimeComponentRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Methods run in separate Android processes, in the order enforced by the lifecycle gate. */
class NativeUpdateLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val host get() = RuntimeComponentRegistry.host()
    private val controller get() = host.updates()
    private val compose = createAndroidComposeRule<MainActivity>()
    private fun grantPermission(permission: String) {
        if (Build.VERSION.SDK_INT >= 28) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        else android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission")
        ).use { it.readBytes() }
    }
    private val prep = object : ExternalResource() {
        override fun before() {
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            grantPermission(permission)
            if (Build.VERSION.SDK_INT >= 33) grantPermission(Manifest.permission.POST_NOTIFICATIONS)
            AppSettings.getInstance(context).setOnboardingCompleted(true)
            context.getSharedPreferences("installed_release_notes", 0).edit().putInt("seen", controller.status().getLong("versionCode").toInt()).commit()
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(prep).around(compose)
    private val proof get() = context.getSharedPreferences("native-update-lifecycle", Context.MODE_PRIVATE)

    @Test fun seedRealUserDataAndStageWithoutConsent() {
        assertCode(OLD)
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        proof.edit().putLong("apkUpdated", info.lastUpdateTime).putString("apkVersion", info.versionName).commit()
        var created: MusicViewModel? = null
        instrumentation.runOnMainSync { created = androidx.lifecycle.ViewModelProvider(compose.activity)[MusicViewModel::class.java] }
        val model = requireNotNull(created)
        await(90_000) { model.isInitialized.value }
        instrumentation.runOnMainSync { model.createPlaylist(PLAYLIST) }
        await { model.playlists.value.any { it.name == PLAYLIST } }
        val playlistId = model.playlists.value.single { it.name == PLAYLIST }.id
        proof.edit().putString("playlistId", playlistId).commit()
        var flush: kotlinx.coroutines.Job? = null
        instrumentation.runOnMainSync { flush = model.ensurePlaylistsSaved() }
        runBlocking { requireNotNull(flush).join() }
        AppSettings.getInstance(context).setShowKeyboardOnSearchOpen(false)
        val asset = copyAsset("next.t2module")
        val corrupted = File(context.cacheDir, "tampered.t2module")
        ZipFile(asset).use { input -> ZipOutputStream(corrupted.outputStream()).use { output ->
            input.entries().asSequence().forEach { entry ->
                output.putNextEntry(ZipEntry(entry.name))
                val bytes = input.getInputStream(entry).use { it.readBytes() }
                output.write(if (entry.name == "notes.md") bytes + 1.toByte() else bytes)
                output.closeEntry()
            }
        } }
        assertThrows(IllegalArgumentException::class.java) { controller.stage(corrupted.path, NEXT) }
        assertNull(controller.status().getBundle("candidate"))
        val staged = controller.stage(asset.path, NEXT)
        proof.edit().putString("nextHash", staged.getString("archiveHash")).commit()
        assertFalse(controller.status().getBundle("candidate")!!.getBoolean("approved"))
        assertCode(OLD)
        evidence("staged-without-consent")
    }

    @Test fun freshProcessStillUsesOldCodeUntilTheUserApproves() {
        assertCode(OLD)
        assertFalse(controller.status().getBundle("candidate")!!.getBoolean("approved"))
        preservedData()
        evidence("unapproved-fresh-process")
    }

    @Test fun cancelThenApproveWhileRealLocalMusicKeepsPlaying() {
        assertCode(OLD)
        val audio = File(context.cacheDir, "approval-audio.m4a")
        instrumentation.context.assets.open("library-fixture.m4a").use { input -> audio.outputStream().use { input.copyTo(it) } }
        ContextCompat.startForegroundService(context, Intent(context, MediaPlaybackService::class.java).setAction(MediaPlaybackService.ACTION_INIT_SERVICE))
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        instrumentation.runOnMainSync {
            future = MediaController.Builder(context, SessionToken(context, ComponentName(context, MediaPlaybackService::class.java))).buildAsync()
        }
        val player = requireNotNull(future).get(30, TimeUnit.SECONDS)
        try {
            instrumentation.runOnMainSync {
                player.setMediaItem(MediaItem.Builder().setMediaId("approval-local").setUri(Uri.fromFile(audio)).build())
                player.prepare(); player.seekTo(8000); player.play()
            }
            await { var playing = false; instrumentation.runOnMainSync { playing = player.isPlaying }; playing }
            val hash = requireNotNull(proof.getString("nextHash", null))
            controller.requestApproval(hash)
            clickDialog(android.R.id.button2)
            assertFalse(controller.status().getBundle("candidate")!!.getBoolean("approved"))
            var before = 0L
            instrumentation.runOnMainSync { before = player.currentPosition }
            controller.requestApproval(hash)
            clickDialog(android.R.id.button1)
            await { controller.status().getBundle("candidate")?.getBoolean("approved") == true }
            assertCode(OLD)
            instrumentation.runOnMainSync {
                assertTrue("Approval must not stop local music", player.isPlaying)
                assertTrue("Approval must not rewind local music", player.currentPosition >= before)
                assertEquals("approval-local", player.currentMediaItem?.mediaId)
            }
            preservedData()
            evidence("approved-with-live-local-playback")
        } finally {
            instrumentation.runOnMainSync { player.stop(); player.clearMediaItems(); player.release() }
            context.stopService(Intent(context, MediaPlaybackService::class.java)); audio.delete()
        }
    }

    @Test fun actualNewDexAndNewResourceRunWithoutAnotherApkInstallation() {
        assertCode(NEXT)
        val feature = host.feature("test")
        val arguments = InstrumentationRegistry.getArguments()
        val resourceName = arguments.getString("updateResourceName") ?: "runtime_update_fixture"
        val expected = arguments.getString("updateResourceTextBase64")?.let {
            String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } ?: "New resource from independent feature v2"
        val symbol = feature.javaClass.classLoader!!.loadClass("chromahub.rhythm.app.R\$string").getField(resourceName)
        val text = host.wrap(context, "test-resource").resources.getString(symbol.getInt(null))
        assertEquals(expected, text)
        assertFalse(controller.status().getBoolean("trialInProgress"))
        preservedData()
        evidence("new-dex-new-resource-same-installed-apk")
    }

    @Test fun approveSignedStartupFailureFixture() {
        assertCode(NEXT)
        val staged = controller.stage(copyAsset("bad-startup.t2module").path, BROKEN)
        val hash = requireNotNull(staged.getString("archiveHash"))
        proof.edit().putString("badHash", hash).commit()
        controller.requestApproval(hash)
        clickDialog(android.R.id.button1)
        await { controller.status().getBundle("candidate")?.getBoolean("approved") == true }
        assertCode(NEXT)
        evidence("approved-signed-startup-failure")
    }

    @Test fun failedStartupRestoresKnownGoodCodeAndKeepsUserData() {
        assertCode(NEXT)
        assertEquals(proof.getString("badHash", null), controller.status().getString("lastFailedArchive"))
        assertFalse(controller.status().getBoolean("trialInProgress"))
        preservedData()
        evidence("failed-update-recovered")
    }

    private fun assertCode(expected: Long) {
        assertEquals(expected, controller.status().getLong("versionCode"))
        assertEquals(expected, host.feature("test").describe().getLong("compiledVersionCode"))
        assertFalse(host.feature("test").describe().getBoolean("failed"))
    }
    private fun preservedData() {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(proof.getLong("apkUpdated", -1), info.lastUpdateTime)
        assertEquals(proof.getString("apkVersion", null), info.versionName)
        assertFalse(AppSettings.getInstance(context).showKeyboardOnSearchOpen.value)
        assertEquals(PLAYLIST, runBlocking { RhythmDatabase.getInstance(context).playlistDao().getPlaylistById(requireNotNull(proof.getString("playlistId", null))) }?.name)
    }
    private fun copyAsset(name: String): File = File(context.cacheDir, name).also { file ->
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
    }
    private fun clickDialog(button: Int) {
        await { runCatching { onView(withId(button)).check(matches(isDisplayed())) }.isSuccess }
        onView(withId(button)).perform(click())
    }
    private fun await(timeout: Long = 30_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) { check(SystemClock.elapsedRealtime() < deadline) { "Native lifecycle condition timed out" }; Thread.sleep(100) }
    }
    private fun evidence(phase: String) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val status = controller.status()
        val record = JSONObject().put("phase", phase).put("featureCode", status.getLong("versionCode"))
            .put("compiledCode", host.feature("test").describe().getLong("compiledVersionCode"))
            .put("apkUpdated", info.lastUpdateTime).put("apkVersion", info.versionName)
            .put("candidateApproved", status.getBundle("candidate")?.getBoolean("approved") ?: false)
        File(context.getExternalFilesDir(null), "native-lifecycle-$phase.json").writeText(record.toString(2))
    }
    private companion object {
        const val OLD = 554991327L
        const val NEXT = 554991328L
        const val BROKEN = 554991329L
        const val PLAYLIST = "Native lifecycle playlist"
    }
}
