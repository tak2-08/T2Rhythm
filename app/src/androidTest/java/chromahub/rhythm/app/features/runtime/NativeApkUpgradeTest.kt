// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.features.local.data.database.RhythmDatabase
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.runtime.api.RuntimeComponentRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest

/** Follows LegacyUpgradeSeedTest and an actual `adb install -r`, without clearing app storage. */
class NativeApkUpgradeTest {
    @Suppress("DEPRECATION")
    @Test fun legacyDataUidAndOriginalSignerSurviveTheBaseConversion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val proof = context.getSharedPreferences("native-apk-upgrade-proof", Context.MODE_PRIVATE)
        assertTrue("Legacy app must seed the proof before installing the new base", proof.contains("uid"))
        assertEquals(proof.getInt("uid", -1), context.applicationInfo.uid)
        assertEquals(proof.getString("dataDir", null), context.applicationInfo.dataDir)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        val signer = if (Build.VERSION.SDK_INT >= 28) info.signingInfo!!.apkContentsSigners[0] else info.signatures!![0]
        val signature = MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(proof.getString("signer", null), signature)
        assertEquals(proof.getLong("firstInstalled", -1), info.firstInstallTime)
        assertEquals("Legacy private music metadata remains intact.", File(context.filesDir, "legacy-native-upgrade-user-file.txt").readText())
        assertFalse(AppSettings.getInstance(context).showKeyboardOnSearchOpen.value)
        assertTrue(AppSettings.getInstance(context).darkMode.value)
        assertTrue(AppSettings.getInstance(context).onboardingCompleted.value)
        assertEquals("Legacy playlist before base conversion", runBlocking {
            RhythmDatabase.getInstance(context).playlistDao().getPlaylistById("legacy-native-upgrade")
        }?.name)
        assertFalse(RuntimeComponentRegistry.host().feature("upgrade-test").describe().getBoolean("failed"))
        assertEquals(554991327L, RuntimeComponentRegistry.host().updates().status().getLong("versionCode"))
    }
}
