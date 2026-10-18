// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.metadata.*
import chromahub.rhythm.app.features.local.data.video.*
import chromahub.rhythm.app.features.local.di.LocalMusicModule
import chromahub.rhythm.app.shared.data.model.AppSettings
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MaintenanceScreensTest {
    @get:Rule val compose = createComposeRule()
    private val base
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun root() =
        File(base.cacheDir, "maintenance-ui-${UUID.randomUUID()}").apply { mkdirs() }

    private fun context(root: File): Context {
        val korean =
            base.createConfigurationContext(
                android.content.res.Configuration(base.resources.configuration).apply {
                    setLocale(java.util.Locale.KOREAN)
                }
            )
        return object : ContextWrapper(korean) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }

            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }

            override fun getDatabasePath(name: String) =
                File(name).takeIf { it.isAbsolute } ?: File(root, name)
        }
    }

    @Test
    fun failedDownloadHasImmediateCombinedDeletionAndBulkCleanup() {
        val root = root()
        val ctx = context(root)
        val failed =
            DownloadRecord(
                UUID.randomUUID().toString(),
                "AUDIO",
                "test",
                "source",
                "실패한 음원",
                state = "FAILED",
                error = "Network error",
            )
        val done =
            failed.copy(
                id = UUID.randomUUID().toString(),
                title = "보관 중인 음원",
                state = "SUCCEEDED",
                fileBytes = 4000000,
            )
        val part =
            File(ctx.cacheDir, "media_transfers/${failed.id}.part").apply {
                parentFile!!.mkdirs()
                writeText("partial")
            }
        DownloadRegistry.save(ctx, failed)
        DownloadRegistry.save(ctx, done)
        try {
            compose.setContent {
                val owner =
                    checkNotNull(androidx.activity.compose.LocalActivityResultRegistryOwner.current)
                CompositionLocalProvider(
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides owner,
                    LocalContext provides ctx,
                    LocalConfiguration provides ctx.resources.configuration,
                ) {
                    MaterialTheme { DownloadCenter {} }
                }
            }
            compose.waitUntil(15000) {
                compose.onAllNodesWithText(failed.title).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("download:${failed.id}").performScrollTo()
            capture("maintenance-downloads.png")
            compose
                .onNode(
                    hasText(ctx.getString(R.string.download_remove_failed)) and
                        hasAnyAncestor(hasTestTag("download:${failed.id}"))
                )
                .performClick()
            compose.waitUntil(15000) {
                DownloadRegistry.get(ctx, failed.id) == null && !part.exists() &&
                    !File(ctx.filesDir, "music_metadata/downloads/${failed.id}.json").exists()
            }
            assertFalse(part.exists())
            compose.onNodeWithTag("download:${done.id}").performScrollTo()
            compose.onNodeWithText(done.title).assertIsDisplayed()
            compose.onNodeWithText(failed.title).assertDoesNotExist()
            capture("maintenance-download-removed.png")
        } finally {
            ctx.getSharedPreferences("removed_downloads", Context.MODE_PRIVATE)
                .edit()
                .remove(failed.id)
                .commit()
            root.deleteRecursively()
        }
    }

    @Test
    fun completedLibraryHasNoEligibleWorkAndExplainsRetentionAtLargeFont() {
        val root = root()
        val ctx = context(root)
        val catalog = CatalogEnrichmentService.get(base)
        val options = catalog.preferences.options.value
        val settings = AppSettings.getInstance(base)
        val integration = settings.integrationsEnabled.value
        settings.setIntegrationsEnabled(true)
        catalog.preferences.update(options.copy(enabled = true))
        val songs = runBlocking { LocalMusicModule.provideMusicRepository(base).loadSongs() }
        org.junit.Assume.assumeTrue(songs.isNotEmpty())
        CatalogBatchHistory(ctx).use { history ->
            songs.forEach { history.complete(it, it, catalog.preferences.options.value) }
        }
        try {
            compose.setContent {
                val owner =
                    checkNotNull(androidx.activity.compose.LocalActivityResultRegistryOwner.current)
                CompositionLocalProvider(
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides owner,
                    LocalContext provides ctx,
                    LocalConfiguration provides ctx.resources.configuration,
                    LocalDensity provides Density(ctx.resources.displayMetrics.density, 1.3f),
                ) {
                    MaterialTheme {
                        Column(
                            Modifier.fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            LibraryEnrichmentPanel()
                            HistoryRetentionPanel()
                        }
                    }
                }
            }
            compose.waitUntil(20000) {
                compose
                    .onAllNodesWithText(ctx.getString(R.string.catalog_all_checked))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag("catalog-batch-start").assertIsNotEnabled()
            compose
                .onNodeWithText(ctx.getString(R.string.catalog_candidates, 0, songs.size))
                .assertExists()
            capture("maintenance-library-complete.png")
            compose
                .onNodeWithText(ctx.getString(R.string.retention_title))
                .performScrollTo()
                .assertIsDisplayed()
            compose
                .onNodeWithText(ctx.getString(R.string.retention_description, 120))
                .assertIsDisplayed()
            capture("maintenance-retention-large-font.png")
        } finally {
            settings.setIntegrationsEnabled(integration)
            catalog.preferences.update(options)
            root.deleteRecursively()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { image ->
            File(base.getExternalFilesDir(null), name).outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }
}
