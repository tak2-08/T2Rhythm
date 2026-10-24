// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.runtime

import android.os.Build
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.features.local.data.database.RhythmDatabase
import chromahub.rhythm.app.features.local.data.database.entity.PlaylistEntity
import chromahub.rhythm.app.shared.data.model.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Compiled only in the pinned original APK checkout; leaves genuine legacy user data for upgrade. */
class LegacyUpgradeSeedTest {
    @Suppress("DEPRECATION")
    @Test fun seedLegacyMusicAndSettingsForTheRealApkUpgrade() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = AppSettings.getInstance(context)
        settings.setOnboardingCompleted(true)
        settings.setShowKeyboardOnSearchOpen(false)
        settings.setDarkMode(true)
        runBlocking {
            RhythmDatabase.getInstance(context).playlistDao().insertPlaylist(
                PlaylistEntity("legacy-native-upgrade", "Legacy playlist before base conversion", 1122, 3344, null))
        }
        File(context.filesDir, "legacy-native-upgrade-user-file.txt").writeText("Legacy private music metadata remains intact.")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        val signer = if (Build.VERSION.SDK_INT >= 28) info.signingInfo!!.apkContentsSigners[0] else info.signatures!![0]
        val signature = MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
        context.getSharedPreferences("native-apk-upgrade-proof", Context.MODE_PRIVATE).edit()
            .putInt("uid", context.applicationInfo.uid).putString("dataDir", context.applicationInfo.dataDir)
            .putLong("firstInstalled", info.firstInstallTime).putString("signer", signature).commit()
    }
}
