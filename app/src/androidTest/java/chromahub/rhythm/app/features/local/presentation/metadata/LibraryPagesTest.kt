// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.R
import chromahub.rhythm.app.core.domain.scan.AlbumNameResolver
import chromahub.rhythm.app.features.local.data.metadata.*
import chromahub.rhythm.app.features.local.di.LocalMusicModule
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.*
import chromahub.rhythm.app.shared.presentation.screens.player.ExpressivePlayerScreen
import chromahub.rhythm.app.shared.presentation.screens.settings.LibrarySettingsScreen
import kotlinx.coroutines.runBlocking
import kr.co.metadata.crawler.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.zip.*
import org.junit.Assert.*
import org.junit.*

class LibraryPagesTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val token = UUID.randomUUID().toString()
    private val roots = mutableListOf<File>()
    private val publicPages = mutableListOf<Pair<EntityKind, String>>()
    private var previousFolder = false
    private fun isolated(): Context {
        val root = File(base.cacheDir, "page-tests-$token-${roots.size}").apply { mkdirs() }; roots += root
        return object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
    }
    @Before @Suppress("DEPRECATION") fun setup() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(base.packageName, Manifest.permission.READ_EXTERNAL_STORAGE)
        compose.runOnUiThread {
            Locale.setDefault(Locale.KOREAN)
            listOf(base.resources, compose.activity.resources).forEach { r -> r.updateConfiguration(Configuration(r.configuration).apply { setLocale(Locale.KOREAN) }, r.displayMetrics) }
            previousFolder = AppSettings.getInstance(base).useFolderNameAsAlbum.value
            AppSettings.getInstance(base).setUseFolderNameAsAlbum(false)
        }
    }
    @After fun cleanup() {
        roots.forEach(File::deleteRecursively)
        publicPages.forEach { (kind, key) -> EntityPageStore(base).file(kind, key).delete() }
        compose.runOnUiThread { AppSettings.getInstance(base).setUseFolderNameAsAlbum(previousFolder) }
    }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(2000); compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(base.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun authoredPageSurvivesCacheDeletionAndLateAutomaticText() = runBlocking {
        val ctx = isolated(); val pages = EntityPageStore(ctx)
        pages.save(EntityKind.ARTIST, "artist", "아티스트", "", "내가 수정한 소개글")
        ctx.cacheDir.deleteRecursively()
        val after = EntityPageStore(ctx).capture(EntityKind.ARTIST, "artist", "아티스트", "", "늦게 도착한 자동 소개글")
        assertEquals("내가 수정한 소개글", after.description)
        assertTrue(after.edited)
        assertEquals(setOf("t2page"), File(ctx.filesDir, "music_metadata/pages").listFiles()!!.filter { !it.name.startsWith(".") }.map { it.extension }.toSet())
        ZipFile(pages.file(EntityKind.ARTIST, "artist")).use { assertNotNull(it.getEntry("page.json")); assertNotNull(it.getEntry(".nomedia")) }
    }
    @Test fun pageBundleRoundTripsArtworkAndRebuildsOnlyAPrivateRenderingCache() = runBlocking {
        val ctx = isolated(); val pages = EntityPageStore(ctx)
        val image = File(ctx.cacheDir, "source.png")
        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(0xff3978aa.toInt()); image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        val page = pages.save(EntityKind.ALBUM, "album", "앨범", "가수", "보관한 앨범 설명", Uri.fromFile(image))
        val out = ByteArrayOutputStream(); pages.export(page, out)
        val archive = out.toByteArray()
        assertTrue(archive.size > 100)
        ctx.cacheDir.deleteRecursively()
        val restored = pages.read(EntityKind.ALBUM, "album")!!
        val preview = File(pages.image(restored)!!.path!!)
        assertTrue(preview.canonicalPath.startsWith(ctx.cacheDir.canonicalPath + "/"))
        assertTrue(File(preview.parentFile, ".nomedia").isFile)
        assertFalse(File(ctx.filesDir, "music_metadata/pages").listFiles()!!.any { it.extension in listOf("jpg", "png", "webp", "json") })
        val imported = pages.importPage(EntityKind.ALBUM, "other", "다른 앨범", "가수", archive.inputStream())
        assertEquals("보관한 앨범 설명", imported.description)
        assertEquals("other", imported.key)
        assertNotNull(pages.image(imported))
    }
    @Test fun importingWrongKindsOrTraversalCannotReplaceAnExistingPage() = runBlocking {
        val ctx = isolated(); val pages = EntityPageStore(ctx)
        val original = pages.save(EntityKind.ALBUM, "album", "앨범", "가수", "기존 소개글")
        val archive = ByteArrayOutputStream().also { pages.export(original, it) }.toByteArray()
        assertTrue(runCatching { pages.importPage(EntityKind.ARTIST, "artist", "가수", "", archive.inputStream()) }.isFailure)
        val bad = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip -> zip.putNextEntry(ZipEntry("../escape.png")); zip.write(byteArrayOf(1)); zip.closeEntry() } }.toByteArray()
        assertTrue(runCatching { pages.importPage(EntityKind.ALBUM, "album", "앨범", "가수", bad.inputStream()) }.isFailure)
        assertEquals("기존 소개글", pages.read(EntityKind.ALBUM, "album")!!.description)
        assertFalse(File(ctx.filesDir, "escape.png").exists())
    }
    @Test fun explicitEmptyAboutAndRemovedImageDoNotResurrectFetchedContent() = runBlocking {
        val key = CatalogEnrichmentService.get(base).artistKey("page-empty-$token"); publicPages += EntityKind.ARTIST to key
        val pages = EntityPageStore(base)
        pages.save(EntityKind.ARTIST, key, "page-empty-$token", "", "", removeImage = true)
        pages.capture(EntityKind.ARTIST, key, "page-empty-$token", "", "다시 받은 글")
        assertNull(LocalMusicModule.provideMusicRepository(base).loadArtistDescription("page-empty-$token", fetch = false))
        val page = pages.read(EntityKind.ARTIST, key)!!
        assertTrue(page.imageEdited); assertNull(pages.image(page)); assertEquals("", page.description)
    }
    @Test fun pageEditorStoresChangesAndReopensTheSavedDescription() {
        val key = "page-editor-$token"; publicPages += EntityKind.ARTIST to key
        compose.setContent { CompositionLocalProvider(LocalContext provides compose.activity) { MaterialTheme {
            EntityPageActions(EntityKind.ARTIST, key, "직접 고치는 아티스트 페이지", description = "기존 소개글", artwork = null)
        } } }
        compose.onNodeWithTag("page-edit").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("page-description").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("page-description").performTextReplacement("내가 보관하고 싶은 아티스트 소개")
        Espresso.closeSoftKeyboard()
        capture("page-description-editor.png")
        compose.onNodeWithText(base.getString(R.string.page_save)).performClick()
        compose.waitUntil(10_000) { EntityPageStore(base).read(EntityKind.ARTIST, key)?.description == "내가 보관하고 싶은 아티스트 소개" }
        compose.onNodeWithTag("page-edit").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("page-description").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("page-description").assertTextContains("내가 보관하고 싶은 아티스트 소개")
    }
    @Test fun expressivePlayerShowsUppercaseGenresAndTheKnownReleaseYear() {
        val song = Song("player-$token", "밤에 듣는 노래", "장르와 출시일", "앨범", duration = 180_000,
            uri = Uri.parse("file:///missing/$token.mp3"), genre = "r&b, Pop", year = 2024)
        var info = 0
        compose.setContent {
            val vm: MusicViewModel = viewModel()
            CompositionLocalProvider(LocalContext provides compose.activity) { MaterialTheme {
                ExpressivePlayerScreen(song, false, false, { .23f }, "0:42", "3:00", 1, 1, false, 0,
                    showLyricsView = false, showLyrics = false, lyrics = null, isLoadingLyrics = false, onlineOnlyLyrics = false,
                    onLyricsSeek = {}, onRetryLyrics = {}, onShowLyricsEditor = {}, onPickLyricsFile = {}, isMediaLoading = false, isSeeking = false,
                    onPlayPause = {}, onSeek = {}, onSkipPrevious = {}, onSkipNext = {}, onToggleFavorite = {}, onToggleShuffle = {}, onToggleRepeat = {},
                    onToggleLyrics = {}, onSongInfoClick = { info++ }, onShowAlbumBottomSheet = {}, onShowArtist = {}, onMoreClick = {}, onDeviceClick = {}, onQueueClick = {}, onBack = {},
                    location = null, appSettings = AppSettings.getInstance(base), musicViewModel = vm)
            } }
        }
        compose.onNodeWithTag("player-track-metadata").assertIsDisplayed()
        compose.onNodeWithText("R&B · POP").assertIsDisplayed()
        compose.onNodeWithText("2024").assertIsDisplayed()
        capture("player-genre-date.png")
        compose.onNodeWithTag("player-track-metadata").performClick(); assertEquals(1, info)
    }
    @Test fun albumFolderFallbackIsDisabledAndCanBeExplicitlyEnabled() {
        val ctx = isolated(); val folder = File(ctx.cacheDir, "다운로드 폴더").apply { mkdirs() }
        val wav = File(folder, "no-album.wav")
        val data = ByteBuffer.allocate(44 + 16000 * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(data.capacity() - 8).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(32000)
        wav.writeBytes(data.array())
        val resolver = AlbumNameResolver(ctx)
        assertEquals("Unknown Album", resolver.resolve(folder.name, wav.path, Uri.fromFile(wav), wav.lastModified()))
        AppSettings.getInstance(base).setUseFolderNameAsAlbum(true)
        assertEquals(folder.name, resolver.resolve("Unknown Album", wav.path, Uri.fromFile(wav), wav.lastModified()))
    }
    @Test fun folderAlbumSettingIsVisibleAndPersistsTheUsersChoice() {
        compose.setContent { CompositionLocalProvider(LocalContext provides compose.activity) { MaterialTheme { LibrarySettingsScreen {} } } }
        compose.onNodeWithText(base.getString(R.string.folder_album_title)).performScrollTo().assertIsDisplayed()
        compose.onNode(isToggleable() and hasAnyAncestor(hasText(base.getString(R.string.folder_album_title), substring = true))).performClick()
        assertTrue(AppSettings.getInstance(base).useFolderNameAsAlbum.value)
        assertTrue(base.getSharedPreferences("rhythm_preferences", 0).getBoolean("use_folder_name_as_album", false))
        capture("folder-album-setting.png")
    }
    @Test fun existingUnopenedAlbumAndArtistPagesAreMaterializedWithTheirLists() = runBlocking {
        val ctx = isolated(); val materializer = EntityPageMaterializer(ctx); val pages = EntityPageStore(ctx)
        val song = Song("song", "첫 번째 트랙", "가수", "앨범 A", duration = 180000, uri = Uri.parse("file:///not-played.mp3"), genre = "pop")
        val a = Album("a", "앨범 A", "가수", year = 2024, songs = listOf(song))
        val b = Album("b", "앨범 B", "가수", year = 2025, songs = listOf(song.copy(album = "앨범 B")))
        materializer.album(a, "앨범 A 설명"); materializer.album(b, "앨범 B 설명")
        materializer.artist(Artist("artist", "가수", albums = listOf(a, b), songs = listOf(song)), "가수 소개")
        assertEquals(3, File(ctx.filesDir, "music_metadata/pages").listFiles()!!.count { it.extension == "t2page" })
        val key = CatalogEnrichmentService.get(base).artistKey("가수")
        val page = pages.read(EntityKind.ARTIST, key)!!
        assertEquals(2, page.albums!!.size); assertEquals("첫 번째 트랙", page.tracks!!.single().title)
        assertEquals("POP", page.tracks!!.single().genre)
        ctx.cacheDir.deleteRecursively()
        assertEquals("가수 소개", pages.read(EntityKind.ARTIST, key)!!.description)
    }
}
