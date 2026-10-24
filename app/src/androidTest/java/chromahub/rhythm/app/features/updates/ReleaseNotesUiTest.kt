// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.updates

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.R
import chromahub.rhythm.app.shared.presentation.viewmodel.AppVersion
import org.junit.*
import org.junit.Assert.*

class ReleaseNotesUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("installed_release_notes", 0)
    private val previous = prefs.getInt("seen", -1)
    @After fun restore() { prefs.edit().putInt("seen", previous).commit() }

    @Test fun installedNotesAppearOnceAndCanBeReopenedWithoutAnUpdateOrNetwork() {
        val version = AppVersion("test-version", 987654321, "2026-10-24", emptyList(), emptyList(), "", releaseNotes = "Offline update notes: playback continuity")
        prefs.edit().putInt("seen", -1).commit()
        var mounted by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            InstalledReleaseNotesCard(version)
            if (mounted) InstalledReleaseNotesPrompt(version, true)
        } }
        compose.onNodeWithText(version.releaseNotes, substring = true).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { image ->
            java.io.File(context.getExternalFilesDir(null), "installed-notes-1326.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
        compose.onNodeWithText(context.getString(R.string.release_notes_done)).performClick()
        assertEquals(version.versionCode, prefs.getInt("seen", -1))
        compose.runOnIdle { mounted = false }
        compose.runOnIdle { mounted = true }
        compose.onNodeWithTag("installed-release-notes-dialog").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.release_notes_open)).performClick()
        compose.onNodeWithText(version.releaseNotes, substring = true).assertIsDisplayed()
    }
    @Test fun longNotesAtLargeFontKeepConfirmationReachable() {
        val version = AppVersion("test-large", 987654322, "2026-10-24", emptyList(), emptyList(), "",
            releaseNotes = (1..70).joinToString("\n") { "- Playback improvement $it" })
        prefs.edit().putInt("seen", -1).commit()
        compose.setContent { MaterialTheme {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.8f)) {
                InstalledReleaseNotesPrompt(version, true)
            }
        } }
        compose.onNodeWithText(context.getString(R.string.release_notes_done)).assertIsDisplayed().performClick()
        compose.onNodeWithTag("installed-release-notes-dialog").assertDoesNotExist()
    }
    @Test fun installedHistoryCanReceiveNotesAfterAnExternalApkInstallation() {
        val journal = UpdateJournal.isolated(context, "notes-test-${System.nanoTime()}")
        journal.reconcile()
        val code = chromahub.rhythm.app.BuildConfig.VERSION_CODE.toLong()
        journal.attachInstalledNotes(code, "Bundled offline changes")
        assertEquals("Bundled offline changes", journal.records.value.first { it.code == code }.notes)
        journal.clear()
        journal.attachInstalledNotes(code, "Must not recreate deleted history")
        assertTrue(journal.records.value.isEmpty())
    }
}
