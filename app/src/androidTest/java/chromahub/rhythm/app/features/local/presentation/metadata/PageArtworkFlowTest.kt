// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.StrictMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.metadata.*
import chromahub.rhythm.app.features.local.di.LocalMusicModule
import chromahub.rhythm.app.features.local.presentation.screens.*
import chromahub.rhythm.app.shared.data.model.*
import chromahub.rhythm.app.shared.presentation.components.dialogs.CustomizeArtistImageDialog
import kotlinx.coroutines.runBlocking
import kr.co.metadata.crawler.*
import java.io.File
import java.util.*
import org.junit.*
import org.junit.Assert.*

class PageArtworkFlowTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val token = UUID.randomUUID().toString()
    private val roots = mutableListOf<File>()
    private val pages = mutableListOf<Pair<EntityKind, String>>()
    private val songs = mutableListOf<Song>()
    private var previousIntegrations = false
    private fun isolated(): Context {
        val root = File(base.cacheDir, "page-flow-$token-${roots.size}").apply { mkdirs() }; roots += root
        return object : ContextWrapper(base) {
            private val privateFiles = File(root, "files").apply { mkdirs() }
            private val privateCache = File(root, "cache").apply { mkdirs() }
            override fun getFilesDir() = privateFiles
            override fun getCacheDir() = privateCache
        }
    }
    @Before @Suppress("DEPRECATION") fun setup() {
        chromahub.rhythm.app.testing.grantTestPermission(
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
        compose.runOnUiThread {
            Locale.setDefault(Locale.KOREAN)
            listOf(base.resources, compose.activity.resources).forEach { r -> r.updateConfiguration(Configuration(r.configuration).apply { setLocale(Locale.KOREAN); fontScale = 1f }, r.displayMetrics) }
            previousIntegrations = AppSettings.getInstance(base).integrationsEnabled.value
            AppSettings.getInstance(base).setIntegrationsEnabled(false)
        }
    }
    @After fun cleanup() {
        roots.forEach(File::deleteRecursively)
        base.cacheDir.listFiles().orEmpty().filter { it.name.endsWith("$token.png") }.forEach(File::delete)
        pages.forEach { (kind,key) -> EntityPageStore(base).file(kind,key).delete() }
        songs.forEach { song ->
            val hash = CatalogFileStore.hash(CatalogEnrichmentService.get(base).songKey(song))
            File(base.filesDir, "music_metadata/lyrics").listFiles().orEmpty().filter { it.name.startsWith("$hash.") }.forEach(File::delete)
            base.getSharedPreferences("catalog_lyrics_writes",0).edit().remove("reviewed:$hash").commit()
        }
        EntityPageStore.invalidate()
        compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.runOnUiThread {
            AppSettings.getInstance(base).setIntegrationsEnabled(previousIntegrations)
            @Suppress("DEPRECATION")
            base.resources.updateConfiguration(Configuration(base.resources.configuration).apply { fontScale = 1f },base.resources.displayMetrics)
        }
    }
    private fun image(ctx: Context, name: String = "cover"): Uri {
        val file = File(ctx.cacheDir, "$name-$token.png")
        val bitmap = Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f,0f,600f,600f,intArrayOf(if(name=="automatic") 0xff168572.toInt() else 0xff122b48.toInt(),0xffae7498.toInt()),null,Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,600f,600f,paint); paint.shader=null; paint.color=0xffe4d6b0.toInt();canvas.drawCircle(350f,240f,140f,paint)
        paint.color=0xff253958.toInt();canvas.drawCircle(380f,205f,130f,paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        return Uri.fromFile(file)
    }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1600); compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync(); Thread.sleep(200)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(base.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    private fun song() = Song("page-$token", "밤의 기록", "달빛", "고요한 밤", duration=180000, uri=Uri.parse("file:///page-$token.mp3"), genre="R&B", year=2025)
    @Test fun hotTextPageReadsUseMemoryAndEditsUpdateBothDiskAndMemory() = runBlocking {
        val ctx=isolated(); val store=EntityPageStore(ctx)
        val first=store.capture(EntityKind.ARTIST,"a","가수","","첫 소개")
        val policy=StrictMode.getThreadPolicy()
        try {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyDeath().build())
            repeat(30) { assertSame(first,EntityPageStore(ctx).read(EntityKind.ARTIST,"a")) }
        } finally { StrictMode.setThreadPolicy(policy) }
        store.save(EntityKind.ARTIST,"a","가수","","새 소개")
        assertEquals("새 소개",EntityPageStore(ctx).read(EntityKind.ARTIST,"a")!!.description)
        EntityPageStore.invalidate()
        assertEquals("새 소개",EntityPageStore(ctx).read(EntityKind.ARTIST,"a")!!.description)
    }
    @Test fun changingAnotherPageDoesNotInvalidateTheOpenPage() = runBlocking {
        val store=EntityPageStore(isolated())
        val a=store.changes(EntityKind.ARTIST,"a"); val b=store.changes(EntityKind.ALBUM,"b")
        repeat(80) { store.changes(EntityKind.ARTIST,"unused-$it") }
        val before=a.value
        store.save(EntityKind.ALBUM,"b","앨범","가수","소개")
        assertEquals(before,a.value);assertTrue(b.value>0)
        store.save(EntityKind.ARTIST,"a","가수","","소개")
        assertTrue(a.value>before)
    }
    @Test fun pageArtworkRehydratesAfterCacheEvictionWithoutExposingImagesToGallery() = runBlocking {
        val ctx=isolated(); val store=EntityPageStore(ctx)
        val original=store.save(EntityKind.ARTIST,"a","가수","","소개",image(ctx))
        File(store.image(original)!!.path!!).setLastModified(1L)
        val oldUri=store.image(original)
        ctx.cacheDir.deleteRecursively()
        val recovered=EntityPageStore(ctx).read(EntityKind.ARTIST,"a")!!
        assertEquals(original.image,recovered.image)
        assertNotEquals("A restored image must restart any painter holding the evicted URI",oldUri,store.image(recovered))
        val preview=File(store.image(recovered)!!.path!!)
        assertTrue(preview.isFile);assertTrue(File(preview.parentFile,".nomedia").isFile)
        assertTrue(File(ctx.filesDir,"music_metadata/pages").listFiles()!!.all { it.extension=="t2page" || it.name==".nomedia" })
    }
    @Test fun lateAutomaticArtworkAndTextCannotOverwriteUserChoices() = runBlocking {
        val ctx=isolated(); val store=EntityPageStore(ctx)
        val user=store.save(EntityKind.ARTIST,"a","가수","","직접 쓴 소개",image(ctx,"mine"))
        val automatic=store.save(EntityKind.ARTIST,"a","가수","","이전 자동 소개",image(ctx,"automatic"),edited=false,customImage=false)
        assertEquals(user.image,automatic.image);assertEquals("직접 쓴 소개",automatic.description)
        assertTrue(automatic.imageEdited);assertTrue(automatic.edited)
    }
    @Test fun imageResetKeepsTheAuthoredDescriptionAndAllowsANewAutomaticImage() = runBlocking {
        val ctx=isolated();val store=EntityPageStore(ctx)
        store.save(EntityKind.ALBUM,"a","앨범","가수","내 소개",image(ctx))
        store.resetImage(EntityKind.ALBUM,"a")
        assertNull(store.read(EntityKind.ALBUM,"a")!!.image)
        val next=store.save(EntityKind.ALBUM,"a","앨범","가수","자동 소개",image(ctx),edited=false,customImage=false)
        assertEquals("내 소개",next.description);assertNotNull(next.image);assertFalse(next.imageEdited)
    }
    @Test fun openingAnArtistSilentlyStoresItsPageAndUsesTheTopEditMenu() = runBlocking {
        val s=song(); val name="달빛"; val catalog=CatalogEnrichmentService.get(base)
        val key=catalog.artistKey(name);pages+=EntityKind.ARTIST to key
        val art=image(base); val artist=Artist("artist-$token",name,artworkUri=art,songs=listOf(s))
        compose.setContent { MaterialTheme {
            ArtistDetailScreen(name, onBack={},onSongClick={},onAlbumClick={},onPlayAll={},onShufflePlay={},onAddToQueue={},onAddSongToPlaylist={},onPlayerClick={},
                artistOverride=artist,songsOverride=listOf(s),albumsOverride=emptyList(),isContentLoadingOverride=false)
        } }
        compose.waitUntil(15000) { EntityPageStore(base).read(EntityKind.ARTIST,key)?.tracks?.isNotEmpty()==true }
        compose.onNodeWithTag("page-edit").assertDoesNotExist()
        compose.onNodeWithText(base.getString(R.string.page_edit)).assertDoesNotExist()
        EntityPageStore(base).save(EntityKind.ARTIST,key,name,"","조용한 밤의 감정을 음악에 담는 아티스트. 섬세한 멜로디와 따뜻한 목소리로 일상의 순간을 노래합니다.")
        compose.waitUntil(10000) { compose.onAllNodesWithText("조용한 밤의 감정",substring=true).fetchSemanticsNodes().isNotEmpty() }
        capture("artist-page-clean.png")
        compose.onNodeWithContentDescription(base.getString(R.string.content_desc_customize_artwork)).performClick()
        compose.onNodeWithTag("entity-edit-menu").assertIsDisplayed();capture("artist-page-edit-menu.png")
        compose.onNodeWithTag("entity-edit-description").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("page-description").fetchSemanticsNodes().singleOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false }
        compose.onNodeWithTag("page-description").performTextReplacement("직접 고친 아티스트 소개입니다.");Espresso.closeSoftKeyboard()
        compose.onNodeWithText(base.getString(R.string.page_save)).performClick()
        compose.waitUntil(10000) { EntityPageStore(base).read(EntityKind.ARTIST,key)?.description=="직접 고친 아티스트 소개입니다." }
        compose.onNodeWithText("직접 고친 아티스트 소개입니다.",substring=true).assertExists()
        Unit
    }
    @Test fun albumPageUsesItsTopEditMenuAndUpdatesTheVisibleDescription() = runBlocking {
        val s=song(); val title="고요한 밤";val key=CatalogEnrichmentService.get(base).albumKey(title,s.artist);pages+=EntityKind.ALBUM to key
        val album=Album("album-$token",title,s.artist,year=2025,songs=listOf(s),artworkUri=image(base))
        EntityPageStore(base).capture(EntityKind.ALBUM,key,title,s.artist,"밤을 닮은 멜로디를 담은 앨범입니다.")
        compose.setContent { MaterialTheme { AlbumDetailScreen(album.id,title,onBack={},onSongClick={},onPlayAll={},onShufflePlay={},onAddToQueue={},onAddSongToPlaylist={},onPlayerClick={},
            albumOverride=album,songsOverride=listOf(s),isContentLoadingOverride=false) } }
        compose.waitUntil(15000) { EntityPageStore(base).read(EntityKind.ALBUM,key)?.tracks?.isNotEmpty()==true }
        compose.onNodeWithTag("page-edit").assertDoesNotExist();capture("album-page-clean.png")
        compose.onNodeWithContentDescription(base.getString(R.string.entity_edit_title,base.getString(R.string.catalog_kind_album))).performClick()
        compose.onNodeWithTag("entity-edit-artwork").assertIsDisplayed();capture("album-page-edit-menu.png")
        compose.onNodeWithTag("entity-edit-description").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("page-description").fetchSemanticsNodes().singleOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false }
        compose.onNodeWithTag("page-description").performTextReplacement("내가 수정한 앨범 소개입니다.");Espresso.closeSoftKeyboard()
        compose.onNodeWithText(base.getString(R.string.page_save)).performClick()
        compose.waitUntil(10000) { EntityPageStore(base).read(EntityKind.ALBUM,key)?.description=="내가 수정한 앨범 소개입니다." }
    }
    @Test fun imageChangeOffersOnlineSourcesAlongsideThePhotoPicker() {
        var online=false
        compose.setContent { MaterialTheme {
            var search by remember { mutableStateOf(false) }
            if (!search) CustomizeArtistImageDialog("달빛",{},{},{},onSearchOnline={online=true;search=true})
            else CatalogArtworkDialog("",EntityKind.ARTIST,{},{})
        } }
        compose.onNodeWithText(base.getString(R.string.entity_online_artwork)).performClick()
        assertTrue(online)
        listOf("Melon","Bugs","Genie").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithTag("artwork-search").assertIsNotEnabled();capture("artist-online-artwork-search.png")
    }
    private fun field(tag: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun document()=CatalogDocument(EntityKind.TRACK,listOf(
        CatalogValue(CatalogField.TRACK_TITLE,"밤의 기록","https://www.melon.com/song/detail.htm?songId=1"),
        CatalogValue(CatalogField.TRACK_IMAGE,image(base).toString(),"https://www.melon.com/song/detail.htm?songId=1"),
        CatalogValue(CatalogField.TRACK_LYRICS,"밤하늘을 따라\n천천히 걸어가\n다시 만날 우리","https://www.melon.com/song/detail.htm?songId=1")))
    @Test fun metadataPickerPreviewsArtworkAndImportsOnlyCheckedLyrics() {
        val doc=document();var result:CatalogDocument?=null
        compose.setContent { MaterialTheme { CatalogLookupDialog("밤의 기록",onDismiss={},onApply={d,_->result=d},initialDocument=doc) } }
        field("catalog-field-TRACK_LYRICS").assertIsOff()
        field("catalog-image-preview").performClick()
        compose.onNodeWithTag("catalog-full-image-preview").assertIsDisplayed();capture("catalog-artwork-preview.png")
        compose.onNodeWithContentDescription(base.getString(R.string.ui_close)).performClick()
        field("catalog-field-TRACK_IMAGE").performClick()
        field("catalog-field-TRACK_LYRICS").performClick();capture("catalog-selectable-lyrics.png")
        compose.onNodeWithTag("catalog-apply").performClick()
        compose.waitUntil(10000) { result!=null }
        assertTrue(result!!.has(CatalogField.TRACK_LYRICS));assertFalse(result!!.has(CatalogField.TRACK_IMAGE))
        assertTrue(result!!.has(CatalogField.TRACK_TITLE))
    }
    @Test fun lyricsSearchStartsWithOnlyLyricsSelected() {
        val doc=document()
        compose.setContent { MaterialTheme { CatalogLookupDialog("밤의 기록",lyricsOnly=true,onDismiss={},onApply={_,_->},initialDocument=doc) } }
        compose.onNodeWithTag("catalog-field-TRACK_TITLE").assertDoesNotExist()
        field("catalog-field-TRACK_LYRICS").assertIsOn()
    }
    @Test fun explicitlyImportedLyricsSurviveAutomaticFetchesAndRepositoryCacheClears() = runBlocking {
        val s=song();songs+=s
        val store=AutomaticLyricsStore.get(base)
        store.saveFetched(s,LyricsData("기존 가사",null),embed=false,reviewed=true)
        val pending=CatalogValue(CatalogField.TRACK_LYRICS,"내가 선택한 가사\n두 번째 줄","https://www.genie.co.kr/detail/songInfo?xgnm=1")
        CatalogLyricImport.data(pending)
        assertEquals("기존 가사",store.read(s)!!.plainLyrics)
        CatalogLyricImport.save(base,s,pending)
        store.saveFetched(s,LyricsData("늦게 받은 자동 가사",null),embed=false)
        assertEquals("내가 선택한 가사\n두 번째 줄",store.readReviewed(s)!!.plainLyrics)
        val repo=LocalMusicModule.provideMusicRepository(base);repo.clearInMemoryCaches()
        val loaded=repo.fetchLyrics(s.artist,s.title,s.id,s.uri,forceRefresh=true)
        assertEquals("내가 선택한 가사\n두 번째 줄",loaded!!.plainLyrics)
    }
    @Test fun songEditorOnlineArtworkOpensTheSharedThreeSourcePicker() {
        val s=song().copy(artworkUri=image(base))
        compose.setContent { MaterialTheme {
            chromahub.rhythm.app.shared.presentation.components.bottomsheets.SongInfoBottomSheet(s,{},AppSettings.getInstance(base),
                onEditSong={_,_,_,_,_,_,_,_,_,_,_,done->done(true)},startInEditMode=true)
        } }
        compose.mainClock.advanceTimeBy(1600); compose.waitForIdle()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitUntil(10_000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true
        }
        val artworkAction = compose.onNodeWithText(base.getString(R.string.songinfobottomsheet_fetch_online_art))
        artworkAction.performScrollTo().assertIsDisplayed().assertIsEnabled()
        capture("song-editor-online-artwork.png")
        artworkAction.assertIsDisplayed().performClick()
        runCatching { compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("artwork-query").assertIsDisplayed() }.isSuccess } }
            .onFailure { chromahub.rhythm.app.testing.snapshotTestUi(compose.activity, "song-editor-artwork-failure") }.getOrThrow()
        compose.onNodeWithTag("artwork-query").assertIsDisplayed()
        listOf("Melon","Bugs","Genie").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText(base.getString(R.string.catalog_close)).performClick()
        compose.onNodeWithTag("artwork-query").assertDoesNotExist()
    }

    @Test fun metadataPickerKeepsApplyReachableWithLargeTextInLandscape() {
        compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        compose.runOnUiThread {
            listOf(base.resources,compose.activity.resources).forEach { resources ->
                @Suppress("DEPRECATION")
                resources.updateConfiguration(Configuration(resources.configuration).apply { setLocale(Locale.KOREAN); fontScale=1.6f },resources.displayMetrics)
            }
        }
        val doc=document()
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalContext provides compose.activity,
                androidx.compose.ui.platform.LocalConfiguration provides compose.activity.resources.configuration,
                LocalDensity provides Density(density.density,1.6f)) {
                MaterialTheme { CatalogLookupDialog("밤의 기록",onDismiss={},onApply={_,_->},initialDocument=doc) }
            }
        }
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(base.getString(R.string.catalog_find_metadata)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("The actual dialog must use large text",layouts.single().layoutInput.density.fontScale>=1.59f)
        compose.onNodeWithTag("catalog-apply").assertIsDisplayed()
        field("catalog-field-TRACK_LYRICS").assertIsDisplayed().assertIsOff()
        capture("catalog-large-text-landscape.png")
    }

}
