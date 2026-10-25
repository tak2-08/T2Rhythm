// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.presentation.components.settings.HomeSectionOrderBottomSheet
import chromahub.rhythm.app.features.local.presentation.components.settings.LanguageHelper
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.shared.presentation.screens.UniversalSearchScreen
import chromahub.rhythm.app.util.LibraryGenreIndex
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

class UserEnhancementsTest {
    private val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prep = object : ExternalResource() {
        override fun before() {
            val settings = AppSettings.getInstance(context)
            settings.setOnboardingCompleted(true)
            settings.setShowKeyboardOnSearchOpen(false)
            settings.setIntegrationsEnabled(false)
            instrumentation.runOnMainSync { LanguageHelper.setLanguage(context, "ko") }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(prep).around(compose)
    @Before @Suppress("DEPRECATION") fun renderKoreanResourcesInTheComponentActivityHarness() {
        compose.runOnUiThread {
            java.util.Locale.setDefault(java.util.Locale.KOREAN)
            listOf(context.resources, compose.activity.resources).forEach { resource ->
                resource.updateConfiguration(android.content.res.Configuration(resource.configuration).apply {
                    setLocale(java.util.Locale.KOREAN)
                }, resource.displayMetrics)
            }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    @Test fun customMixKeepsRefreshAndShelfAndRemovesTheMarkedMoveButton() {
        compose.setContent { MaterialTheme { PersonalCollectionsContent(emptyList(), emptyList(), null, { _, _ -> }, { _, _ -> }) } }
        compose.onNodeWithText(context.getString(R.string.personal_collections_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.personal_refresh_mix)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.personal_mix_shelf)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.personal_move_section)).assertDoesNotExist()
        capture("enhancements-home-mix.png")
    }
    @Test fun searchInputHasTwelveDpAboveItOnBothTabs() {
        var pixels = 0f
        compose.setContent {
            pixels = with(LocalDensity.current) { 12.dp.toPx() }
            MaterialTheme { UniversalSearchScreen() }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("universal-search-input-row").fetchSemanticsNodes().isNotEmpty() }
        fun checkGap() {
            val tabs = compose.onNodeWithTag("universal-search-tabs").fetchSemanticsNode().boundsInRoot
            val input = compose.onNodeWithTag("universal-search-input-row").fetchSemanticsNode().boundsInRoot
            assertEquals(pixels.toDouble(), (input.top - tabs.bottom).toDouble(), 1.0)
        }
        checkGap()
        compose.onNodeWithText(context.getString(R.string.genre_search_tab)).performClick()
        checkGap()
        capture("enhancements-search-spacing.png")
    }
    @Test fun artistsHaveAnEditorEntryThatControlsTheSameHomeVisibilityPreference() {
        val settings = AppSettings.getInstance(context)
        settings.setHomeSectionOrder(listOf("ARTISTS", "RECOMMENDED"))
        settings.setHomeShowArtists(true)
        var shown by mutableStateOf(true)
        compose.setContent { MaterialTheme { if (shown) HomeSectionOrderBottomSheet({ shown = false }, settings) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("home-visibility:ARTISTS").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.home_top_artists)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_top_artists_subtitle)).assertIsDisplayed()
        capture("enhancements-home-layout.png")
        compose.onNodeWithTag("home-visibility:ARTISTS").performClick()
        compose.onNodeWithText(context.getString(R.string.bottomsheet_save)).performClick()
        compose.waitUntil(10_000) { !settings.homeShowArtists.value }
        assertTrue("ARTISTS" in settings.homeSectionOrder.value)
    }
    @Test fun genreCacheSurvivesAnotherLibraryAndInvalidatesRemovedAndEditedLabels() = runBlocking {
        val root = File(context.cacheDir, "genre-cache-${System.nanoTime()}").apply { mkdirs() }
        fun isolated(name: String) = object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "$name/files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "$name/cache").apply { mkdirs() }
        }
        val first = isolated("first"); val other = isolated("other")
        val song = Song("index", "Cached", "Artist", "Album", duration = 1000, uri = Uri.parse("content://test/cache-song"), genre = "CUSTOM GENRE")
        try {
            assertEquals(1, LibraryGenreIndex.get(first, listOf(song)).count("CUSTOM GENRE"))
            assertTrue(File(first.cacheDir, "library-genres-v1.json").isFile)
            LibraryGenreIndex.get(other, emptyList())
            assertEquals(1, LibraryGenreIndex.get(first, listOf(song)).count("CUSTOM GENRE"))
            val updated = LibraryGenreIndex.get(first, listOf(song.copy(genre = "NEW GENRE")))
            assertEquals(0, updated.count("CUSTOM GENRE"))
            assertEquals(1, updated.count("NEW GENRE"))
            assertTrue(updated.genres.size > 100)
            assertEquals(0, LibraryGenreIndex.get(first, emptyList()).count("NEW GENRE"))
        } finally { root.deleteRecursively() }
    }
    @Test fun compactIndexesCloseOnAndroid8WithoutDependingOnNewPlatformInterfaces() {
        val root = File(context.cacheDir, "closeable-index-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getCacheDir() = root }
        val opened = mutableListOf<android.database.sqlite.SQLiteDatabase>()
        try {
            chromahub.rhythm.app.features.local.data.experience.ContextPreferenceIndex(isolated).use { opened += it.readableDatabase }
            chromahub.rhythm.app.features.local.data.discovery.EnrichmentLearningIndex(isolated).use { opened += it.readableDatabase }
            assertEquals(2, opened.size)
            assertTrue(opened.all { !it.isOpen })
        } finally { root.deleteRecursively() }
    }
}
