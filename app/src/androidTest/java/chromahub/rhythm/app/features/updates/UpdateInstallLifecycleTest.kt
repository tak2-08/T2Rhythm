// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.updates

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

/** Prepares storage, then observes a real adb package replacement across two processes. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 28)
class UpdateInstallLifecycleTest {
    @Test fun realPackageReplacementConfirmsHistoryAndRemovesOnlyTheUsedApk() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val phase = InstrumentationRegistry.getArguments().getString("updateLifecyclePhase")
        require(phase in setOf("prepare", "seed", "verify"))
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "verified-updates/lifecycle-future.apk")
        if (phase == "prepare") {
            assertTrue(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            File(context.getExternalFilesDir(null), "update-lifecycle-path.txt").writeText(file.absolutePath)
            return
        }
        val journal = UpdateJournal.get(context)
        if (phase == "seed") {
            assertTrue("Missing fixture ${file.path}; files=${file.parentFile!!.list()?.toList()}", file.length() > 0)
            val archive = context.packageManager.getPackageArchiveInfo(file.path, 0)!!
            val installed = context.packageManager.getPackageInfo(context.packageName, 0)
            assertTrue("Fixture ${archive.longVersionCode} must upgrade ${installed.longVersionCode}", archive.longVersionCode > installed.longVersionCode)
            journal.ready(file, "Real package replacement verification")
            journal.installing(file)
            context.getSharedPreferences("app_updater_downloads", Context.MODE_PRIVATE).edit().putString("downloaded_file", file.path).commit()
            assertEquals(UpdateStage.INSTALL_REQUESTED, journal.records.value.first { it.file == file.path }.stage)
        } else {
            val deadline = SystemClock.elapsedRealtime() + 60_000
            // The package receiver / application worker must do this, not this test.
            while (SystemClock.elapsedRealtime() < deadline) {
                val complete = !file.exists() && journal.records.value.any { it.file == file.path && it.apkRemoved } &&
                    context.getSharedPreferences("app_updater_downloads", Context.MODE_PRIVATE).getString("downloaded_file", null) == null
                if (complete) break
                SystemClock.sleep(200)
            }
            assertFalse("The package replacement worker must remove the installation APK", file.exists())
            val observed = context.packageManager.getPackageInfo(context.packageName, 0)
            val record = journal.records.value.first { it.file == file.path }
            assertEquals(UpdateStage.INSTALLED, record.stage); assertEquals(observed.longVersionCode, record.code); assertTrue(record.apkRemoved)
            assertNull(context.getSharedPreferences("app_updater_downloads", Context.MODE_PRIVATE).getString("downloaded_file", null))
            File(context.getExternalFilesDir(null), "update-lifecycle-evidence.json").writeText(JSONObject()
                .put("installedCode", observed.longVersionCode).put("historyCode", record.code).put("stage", record.stage.name)
                .put("apkRemoved", record.apkRemoved).put("apkExists", file.exists()).put("packageUpdatedAt", observed.lastUpdateTime).toString())
        }
    }
}
