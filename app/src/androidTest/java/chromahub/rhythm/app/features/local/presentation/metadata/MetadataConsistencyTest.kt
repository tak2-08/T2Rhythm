// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.features.local.data.database.RhythmDatabase
import chromahub.rhythm.app.features.local.data.database.entity.toSong
import chromahub.rhythm.app.features.local.data.metadata.*
import chromahub.rhythm.app.features.local.data.repository.MusicRepository
import chromahub.rhythm.app.features.local.di.LocalMusicModule
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kr.co.metadata.crawler.*
import org.junit.*
import org.junit.Assert.*

class MetadataConsistencyTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val token = UUID.randomUUID().toString()
    private val catalog get() = CatalogEnrichmentService.get(context)
    private val fixtures = mutableListOf<Song>()
    private val pages = mutableListOf<Pair<EntityKind, String>>()
    private lateinit var previous: CatalogOptions
    private var integrations = false
    @Before fun setup() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
        previous = catalog.preferences.options.value
        integrations = AppSettings.getInstance(context).integrationsEnabled.value
        AppSettings.getInstance(context).setIntegrationsEnabled(false)
        catalog.preferences.update(previous.copy(replaceTags = true, strictDuration = false, permanent = true))
    }
    @After fun cleanup() = runBlocking {
        fixtures.forEach { song ->
            runCatching { context.contentResolver.delete(song.uri, null, null) }
            val key = catalog.songKey(song)
            val hash = CatalogFileStore.hash(key)
            File(context.filesDir, "music_metadata/manual_tags/$hash.json").delete()
            File(context.filesDir, "music_metadata/lyrics").listFiles().orEmpty().filter { it.name.startsWith("$hash.") }.forEach(File::delete)
            context.getSharedPreferences("catalog_lyrics_writes", 0).edit().remove("reviewed:$hash").remove("stale:$hash").remove("metadata:$hash").commit()
            File(catalog.root, "records/track-$hash.json").delete()
            File(catalog.root, "records/track-${CatalogFileStore.hash("manual:$key")}.json").delete()
            RhythmDatabase.getInstance(context).songDao().deleteByIds(listOf(song.id))
        }
        pages.forEach { (kind, key) -> EntityPageStore(context).file(kind, key).delete() }
        catalog.preferences.update(previous); catalog.reloadStored(); EntityPageStore.invalidate()
        LocalMusicModule.provideMusicRepository(context).clearInMemoryCaches()
        AppSettings.getInstance(context).setIntegrationsEnabled(integrations)
    }
    private fun song() = Song("metadata-$token-${fixtures.size}", "이전 제목", "이전 가수-$token", "이전 앨범", duration = 180_000,
        uri = Uri.parse("file:///metadata-$token-${fixtures.size}.mp3"), path = "/storage/emulated/0/Music/$token/${fixtures.size}.mp3",
        albumArtist = "이전 가수-$token", genre = "POP", year = 2020, trackNumber = 1).also { fixtures += it }
    private fun automatic(song: Song) {
        val values = listOf(CatalogField.TRACK_TITLE to "자동 제목", CatalogField.TRACK_ARTISTS to "자동 가수", CatalogField.TRACK_ALBUM_TITLE to "자동 앨범",
            CatalogField.TRACK_GENRES to "pop", CatalogField.TRACK_RELEASE_DATE to "2020-01-01").map { CatalogValue(it.first, it.second, "https://www.melon.com/song/detail.htm?songId=1") }
        CatalogFileStore(File(catalog.root, "records")).merge(EntityKind.TRACK, catalog.songKey(song), CatalogDocument(EntityKind.TRACK, values), replace = true)
        catalog.reloadStored()
    }
    @Test fun explicitCatalogSelectionWinsWarmAutomaticCacheAndReload() {
        val original = song(); automatic(original)
        assertEquals("자동 제목", catalog.apply(original).title)
        val guessedSong = catalog.apply(original)
        val lyrics = AutomaticLyricsStore.get(context)
        runBlocking { lyrics.saveFetched(guessedSong, LyricsData("이전 자동 가사", null), embed = false) }
        val lyricFile = File(lyrics.root, CatalogFileStore.hash(catalog.songKey(original)) + ".data.json")
        val priorBytes = lyricFile.readBytes()
        val edited = original.copy(title = "내가 선택한 제목", artist = "새 가수", album = "새 앨범", genre = "r&b, jazz", year = 2025,
            albumArtist = "새 앨범 가수", trackNumber = 7, discNumber = 2)
        catalog.recordManualSong(edited)
        lyrics.invalidateUnreviewedForMetadata(edited)
        assertNull(lyrics.read(edited))
        runBlocking { lyrics.saveFetched(guessedSong, LyricsData("늦게 도착한 잘못된 가사", null), embed = false) }
        assertNull(lyrics.read(edited)); assertArrayEquals(priorBytes, lyricFile.readBytes())
        fun verify() { assertEquals(edited.copy(genre = "R&B, JAZZ"), catalog.apply(original)) }
        verify(); catalog.reloadStored(); verify()
        ManualSongMetadata.invalidate(); verify()
    }
    @Test fun deliberateEmptyTagsAndTemporaryAutomaticCacheDoNotLoseManualEdits() {
        val original = song(); automatic(original)
        catalog.preferences.update(catalog.preferences.options.value.copy(permanent = false))
        val cleared = original.copy(title = "수동 제목", genre = null, year = 0, albumArtist = "", trackNumber = 0, discNumber = 3)
        catalog.recordManualSong(cleared)
        catalog.reloadStored()
        assertEquals(cleared, catalog.apply(original))
        val tags = File(context.filesDir, "music_metadata/manual_tags/${CatalogFileStore.hash(catalog.songKey(original))}.json")
        assertTrue(tags.isFile)
        File(tags.path + ".bak").writeBytes(tags.readBytes()); assertTrue(tags.delete())
        catalog.reloadStored()
        assertEquals(cleared, catalog.apply(original)); assertTrue(tags.isFile)
    }
    @Test fun albumOnlyEditRefreshesArtistAlbumCountsAndFolderContents() = runBlocking {
        val first = song(); val second = song().copy(album = first.album)
        val repo = LocalMusicModule.provideMusicRepository(context)
        val settings = AppSettings.getInstance(context)
        val grouping = settings.groupByAlbumArtist.value
        try {
            repo.persistEditedSongs(listOf(first, second))
            val artist = repo.loadArtists().single { it.name == first.artist }
            assertEquals(1, artist.numberOfAlbums)
            repo.getFolderTree()
            val edited = first.copy(album = "나눈 앨범")
            catalog.recordManualSong(edited)
            repo.persistEditedSongs(listOf(edited, second))
            assertEquals(2, repo.loadArtists().single { it.name == first.artist }.numberOfAlbums)
            fun all(node: FolderNode): List<Song> = node.songs + node.subFolders.values.flatMap(::all)
            assertEquals("나눈 앨범", all(repo.getFolderTree()).single { it.id == first.id }.album)
            val cold = MusicRepository(context)
            assertEquals("나눈 앨범", cold.loadSongs().single { it.id == first.id }.album)
        } finally { settings.setGroupByAlbumArtist(grouping) }
    }
    private fun fixture(): Song {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "metadata-$token.m4a"); put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/T2Rhythm-$token/"); put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
        InstrumentationRegistry.getInstrumentation().context.assets.open("audio-test.m4a").use { input -> context.contentResolver.openOutputStream(uri)!!.use { input.copyTo(it) } }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        val path = context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)!!.use { it.moveToFirst(); it.getString(0) }
        return Song(ContentUris.parseId(uri).toString(), "저장 전 제목", "저장 전 가수-$token", "저장 전 앨범", duration = 10_000,
            uri = uri, path = path, genre = "POP", year = 2020, albumArtist = "저장 전 가수-$token").also { fixtures += it }
    }
    @Suppress("UNCHECKED_CAST") private fun <T> state(vm: MusicViewModel, name: String) = MusicViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>
    @Test fun actualFileSaveCompletesAfterLibraryQueuePlaylistsAndPagesRefresh() = runBlocking {
        val original = fixture()
        val repo = LocalMusicModule.provideMusicRepository(context)
        repo.persistEditedSongs(listOf(original))
        val preserved = LyricsData("내가 보관한 가사", null)
        AutomaticLyricsStore.get(context).saveFetched(original, preserved, embed = false, reviewed = true)
        val legacy = File(context.filesDir, "lyrics/" + chromahub.rhythm.app.util.LyricsCacheFiles.name(original.artist, original.title))
        legacy.parentFile!!.mkdirs(); legacy.writeText(com.google.gson.Gson().toJson(preserved))
        val edited = original.copy(title = "연결된 새 제목", artist = "새 가수-$token", album = "새 앨범", genre = "JAZZ", year = 2025,
            albumArtist = "새 앨범 가수-$token", trackNumber = 4, discNumber = 2)
        val store = EntityPageStore(context)
        fun page(song: Song) {
            val key = catalog.albumKey(song.album, song.albumArtist!!); pages += EntityKind.ALBUM to key
            store.snapshot(EntityKind.ALBUM, key, song.album, song.albumArtist.orEmpty(), "직접 보관한 설명", listOf(PageTrack(original.title, original.artist, original.album, original.duration, original.year, original.genre, catalog.songKey(original))))
        }
        page(original); page(edited)
        val oldArtistKey = catalog.artistKey(original.artist); pages += EntityKind.ARTIST to oldArtistKey
        store.snapshot(EntityKind.ARTIST, oldArtistKey, original.artist, "", "아티스트 소개",
            listOf(PageTrack(original.title, original.artist, original.album, original.duration, original.year, original.genre, catalog.songKey(original))),
            listOf(PageAlbum(original.album, original.artist, original.year)))
        lateinit var vm: MusicViewModel
        compose.setContent { vm = androidx.lifecycle.viewmodel.compose.viewModel(); MaterialTheme {} }
        compose.waitUntil(30_000) { vm.isInitialized.value && vm.songs.value.any { it.id == original.id } }
        compose.runOnUiThread {
            state<Queue>(vm, "_currentQueue").value = Queue(listOf(original, original))
            state<List<Playlist>>(vm, "_playlists").value = listOf(Playlist("manual-$token", "보관한 목록", listOf(original)))
        }
        var completed = false; var error: String? = null
        compose.runOnUiThread {
            vm.saveMetadataChanges(original, edited.title, edited.artist, edited.album, edited.genre!!, edited.year, edited.trackNumber,
                albumArtist = edited.albumArtist, discNumber = edited.discNumber,
                onSuccess = {
                    assertEquals(edited.title, vm.songs.value.single { it.id == original.id }.title)
                    assertEquals(listOf(edited.title, edited.title), vm.currentQueue.value.songs.map { it.title })
                    assertEquals(edited.title, vm.playlists.value.single { it.id == "manual-$token" }.songs.single().title)
                    completed = true
                }, onError = { error = it })
        }
        compose.waitUntil(60_000) { completed || error != null }
        assertNull(error); assertTrue(completed)
        assertTrue("Tag refresh must not delete saved lyric files", legacy.isFile)
        assertEquals(preserved, AutomaticLyricsStore.get(context).readReviewed(edited))
        legacy.delete()
        val row = RhythmDatabase.getInstance(context).songDao().getSongById(original.id)!!.toSong()
        assertEquals(edited.title, row.title); assertEquals(edited.artist, row.artist); assertEquals(edited.album, row.album)
        assertEquals(edited.genre, row.genre); assertEquals(edited.year, row.year); assertEquals(edited.discNumber, row.discNumber)
        assertEquals(edited.albumArtist, row.albumArtist); assertEquals(edited.trackNumber, row.trackNumber)
        assertTrue(store.read(EntityKind.ALBUM, catalog.albumKey(original.album, original.albumArtist!!))!!.tracks.orEmpty().isEmpty())
        val newPage = store.read(EntityKind.ALBUM, catalog.albumKey(edited.album, edited.albumArtist!!))!!
        assertEquals("직접 보관한 설명", newPage.description); assertEquals(edited.title, newPage.tracks.orEmpty().single().title)
        val oldKey = catalog.albumKey(original.album, original.albumArtist!!)
        store.snapshot(EntityKind.ALBUM, oldKey, original.album, original.albumArtist.orEmpty(), "늦은 자동 설명",
            listOf(PageTrack(original.title, original.artist, original.album, original.duration, original.year, original.genre, catalog.songKey(original))))
        assertTrue(store.read(EntityKind.ALBUM, oldKey)!!.tracks.orEmpty().isEmpty())
        store.snapshot(EntityKind.ARTIST, oldArtistKey, original.artist, "", "늦은 소개",
            listOf(PageTrack(original.title, original.artist, original.album, original.duration, original.year, original.genre, catalog.songKey(original))),
            listOf(PageAlbum(original.album, original.artist, original.year)))
        assertTrue(store.read(EntityKind.ARTIST, oldArtistKey)!!.tracks.orEmpty().isEmpty())
        assertTrue(store.read(EntityKind.ARTIST, oldArtistKey)!!.albums.orEmpty().isEmpty())
        // Reusing an old song object for a cover edit must not restore its old tags.
        vm.updateCurrentSongMetadata(original.copy(artworkUri = null))
        compose.waitUntil(10_000) { vm.songs.value.single { it.id == original.id }.title == edited.title }
        Unit
    }
}
