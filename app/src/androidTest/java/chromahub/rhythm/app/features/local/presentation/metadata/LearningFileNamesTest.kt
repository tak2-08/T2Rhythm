// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.database.RhythmDatabase
import chromahub.rhythm.app.features.local.data.database.entity.toEntity
import chromahub.rhythm.app.features.local.data.discovery.*
import chromahub.rhythm.app.features.local.data.experience.*
import chromahub.rhythm.app.features.local.data.metadata.*
import chromahub.rhythm.app.features.local.data.video.*
import chromahub.rhythm.app.features.local.di.LocalMusicModule
import chromahub.rhythm.app.shared.data.model.*
import chromahub.rhythm.app.shared.presentation.screens.settings.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.*
import kotlinx.coroutines.runBlocking
import kr.co.metadata.crawler.CatalogFileStore
import org.junit.Assert.*
import org.junit.*

class LearningFileNamesTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings get() = AppSettings.getInstance(context)
    private val token = UUID.randomUUID().toString()
    private val fixtures = mutableListOf<Song>()
    private val owned = mutableListOf<File>()
    private var previousBattery = 75; private var previousAuto = false; private var previousFormat = "ARTIST_TITLE"; private var previousLearning = true
    @Before @Suppress("DEPRECATION") fun setup() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
        compose.runOnUiThread {
            Locale.setDefault(Locale.KOREAN)
            listOf(context.resources, compose.activity.resources).forEach { r -> r.updateConfiguration(Configuration(r.configuration).apply { setLocale(Locale.KOREAN) }, r.displayMetrics) }
            previousBattery = settings.analysisMinimumBattery.value; previousAuto = settings.autoFileNames.value
            previousFormat = settings.fileNameFormat.value; previousLearning = settings.learningUiEnabled.value
            settings.setAnalysisMinimumBattery(75); settings.setAutoFileNames(false); settings.setFileNameFormat("ARTIST_TITLE")
            FileNameWorker.stop(context)
        }
    }
    @After fun cleanup() = runBlocking {
        fixtures.forEach { song ->
            runCatching { context.contentResolver.delete(song.uri, null, null) }
            runCatching { GenreCorrections(context).remove(song.uri.toString()) }
            val key = CatalogEnrichmentService.get(context).songKey(song)
            listOf("music_metadata/artwork_choices/${CatalogFileStore.hash(key)}.json",
                "music_metadata/videos/records/${CatalogFileStore.hash(key)}.json").forEach { File(context.filesDir, it).delete() }
            val lyricKey = CatalogFileStore.hash(key)
            File(context.filesDir, "music_metadata/lyrics").listFiles().orEmpty().filter { it.name.startsWith(lyricKey + ".") }.forEach(File::delete)
            ListeningLedger.get(context).writableDatabase.delete("features", "song_key=?", arrayOf(song.uri.toString()))
        }
        owned.forEach(File::delete)
        SongFileIdentity.invalidate(); SongArtworkOverrides.invalidate()
        LocalMusicModule.provideMusicRepository(context).clearInMemoryCaches()
        compose.runOnUiThread {
            settings.setAnalysisMinimumBattery(previousBattery); settings.setAutoFileNames(previousAuto)
            settings.setFileNameFormat(previousFormat); settings.setLearningUiEnabled(previousLearning)
        }
    }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1200); compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(200) // Let the device draw the frame before UiAutomation captures the surface.
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun wav(title: String?, artist: String = "Test Artist"): ByteArray {
        fun integer(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        fun chunk(name: String, data: ByteArray) = ByteArrayOutputStream().apply { write(name.toByteArray()); write(integer(data.size)); write(data); if (data.size % 2 == 1) write(0) }.toByteArray()
        val body = ByteArrayOutputStream(); body.write("WAVE".toByteArray())
        body.write(chunk("fmt ", ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).array()))
        if (title != null) body.write(chunk("LIST", ByteArrayOutputStream().apply {
            write("INFO".toByteArray()); write(chunk("INAM", (title + "\u0000").toByteArray())); write(chunk("IART", (artist + "\u0000").toByteArray()))
        }.toByteArray()))
        val pcm = ByteBuffer.allocate(31 * 16000 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(31 * 16000) { pcm.putShort((sin(2 * PI * 440 * it / 16000) * 8000).toInt().toShort()) }
        body.write(chunk("data", pcm.array()))
        return ByteArrayOutputStream().apply { write("RIFF".toByteArray()); write(integer(body.size())); write(body.toByteArray()) }.toByteArray()
    }
    private fun fixture(name: String = "__messy_${UUID.randomUUID()}.wav", title: String? = "Test Song"): Song {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name); put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/T2Rhythm-$token/"); put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write(wav(title)) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        val path = context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)!!.use { it.moveToFirst(); it.getString(0) }
        val modified = File(path).lastModified() / 1000 * 1000
        val song = Song(ContentUris.parseId(uri).toString(), title ?: name, "Test Artist", "검사용 앨범", duration = 31_000,
            uri = uri, path = path, dateModified = modified, genre = "POP")
        fixtures += song; return song
    }
    @Test fun batterySliderUsesSeventyFiveAndPersistsTheFullSupportedRange() {
        compose.setContent { CompositionLocalProvider(LocalContext provides compose.activity) { MaterialTheme { AnalysisPowerSetting() } } }
        compose.onNodeWithText(context.getString(R.string.analysis_battery_value, 75)).assertIsDisplayed().performClick()
        compose.onNodeWithTag("analysis-battery-slider").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(95f)) }
        compose.onNodeWithText(context.getString(R.string.analysis_battery_value, 95)).assertIsDisplayed()
        capture("learning-battery-range.png")
        compose.onNodeWithText(context.getString(R.string.page_save)).performClick()
        assertEquals(95, settings.analysisMinimumBattery.value)
        assertEquals(95, settings.prefs.getInt("analysis_minimum_battery", 0))
        compose.onNodeWithTag("analysis-battery-setting").performClick()
        compose.onNodeWithTag("analysis-battery-slider").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(50f)) }
        compose.onNodeWithText(context.getString(R.string.page_save)).performClick()
        assertEquals(50, settings.analysisMinimumBattery.value)
    }
    @Test fun cancellingBatteryChangesPreservesTheSavedThreshold() {
        compose.setContent { CompositionLocalProvider(LocalContext provides compose.activity) { MaterialTheme { AnalysisPowerSetting() } } }
        compose.onNodeWithTag("analysis-battery-setting").performClick()
        compose.onNodeWithTag("analysis-battery-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(95f) }
        compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        assertEquals(75, settings.analysisMinimumBattery.value)
    }
    @Test fun actualEmbeddedTagsOverrideTheFilenameAndFallbackLibraryTitle() = runBlocking {
        val song = fixture(); val plan = FileNameService(context).plan(song.copy(title = "잘못된 목록 제목", artist = "다른 이름"), "ARTIST_TITLE")!!
        assertEquals("Test Artist - Test Song.wav", plan.name)
        assertTrue(File(plan.oldPath).isFile); assertFalse(File(plan.newPath).exists())
    }
    @Test fun renameKeepsBytesUriTagsAndManualLinksThroughADatabaseRebuild() = runBlocking {
        val song = fixture(); val source = File(song.path!!); val original = context.contentResolver.openInputStream(song.uri)!!.use { it.readBytes() }
        val key = CatalogEnrichmentService.get(context).songKey(song)
        val image = File(context.filesDir, "rename-$token.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }; owned += image
        SongArtworkOverrides(context).save(song, Uri.fromFile(image))
        val video = File(context.filesDir, "music_metadata/videos/rename-$token.mp4").apply { parentFile!!.mkdirs(); writeText("linked video") }; owned += video
        MusicVideoRepository(context).save(MusicVideo(key, "local", fileName = video.name, bytes = video.length()))
        val lyrics = LyricsData(plainLyrics = "보관한 첫 가사\n다음 가사\n마지막 가사", syncedLyrics = null, source = "local text")
        AutomaticLyricsStore.get(context).preserveForRename(song, lyrics)
        GenreCorrections(context).put(song, "jazz")
        RhythmDatabase.getInstance(context).songDao().upsertAll(listOf(song.toEntity()))
        val service = FileNameService(context); val plan = service.plan(song, "ARTIST_TITLE")!!
        val outcome = service.rename(plan); assertEquals("DONE", outcome.state)
        val after = outcome.after!!
        assertEquals(song.uri, after.uri); assertFalse(source.exists()); assertArrayEquals(original, context.contentResolver.openInputStream(after.uri)!!.use { it.readBytes() })
        assertEquals(EnrichmentStore.signature(song), EnrichmentStore.signature(after))
        // Reproduce a background scanner publishing its pre-rename snapshot after the rename.
        val repository = LocalMusicModule.provideMusicRepository(context)
        repository.persistEditedSongs(listOf(song))
        assertEquals(after.path, repository.storedSongs(listOf(song)).single().path)
        assertEquals(song.path, SongFileIdentity.currentLocation(context,
            song.copy(uri = Uri.parse("content://media/external/audio/media/999999999"))).path)
        assertEquals(after.path, RhythmDatabase.getInstance(context).songDao().getSongById(song.id)!!.path)
        assertEquals("JAZZ", GenreCorrections(context).apply(listOf(after)).single().genre)
        LocalMusicModule.provideMusicRepository(context).clearSongCacheData()
        SongFileIdentity.invalidate(); SongArtworkOverrides.invalidate()
        assertEquals(Uri.fromFile(image).toString(), SongArtworkOverrides(context).read(after)!!.uri)
        assertEquals(video.name, MusicVideoRepository(context).get(after)!!.fileName)
        assertEquals(lyrics, AutomaticLyricsStore.get(context).read(after))
        assertNull(service.plan(after, "ARTIST_TITLE"))
    }
    @Test fun existingNamesAndFilesChangedAfterPreviewAreNeverOverwritten() = runBlocking {
        val song = fixture(); val service = FileNameService(context); val plan = service.plan(song, "ARTIST_TITLE")!!
        val other = fixture(plan.name); val bytes = context.contentResolver.openInputStream(other.uri)!!.use { it.readBytes() }
        assertEquals("COLLISION", service.rename(plan).state)
        assertArrayEquals(bytes, context.contentResolver.openInputStream(other.uri)!!.use { it.readBytes() }); assertTrue(File(song.path!!).isFile)
        val next = service.plan(song, "ARTIST_TITLE")!!; assertTrue(next.name.contains(" (2)"))
        // Providers may interpret append modes differently. Seek a writable descriptor and
        // verify that this fixture really changed before testing the production stale guard.
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(context.contentResolver.openFileDescriptor(song.uri, "rw")!!).use {
            it.channel.position(it.channel.size()); it.write(0); it.flush(); it.fd.sync()
        }
        assertEquals(next.bytes + 1, File(song.path!!).length())
        assertEquals("STALE", service.rename(next).state)
        assertArrayEquals(bytes, context.contentResolver.openInputStream(other.uri)!!.use { it.readBytes() })
    }
    @Test fun untaggedAndCurrentlyPlayingAudioAreDeferred() = runBlocking {
        val missing = fixture(title = null); val service = FileNameService(context)
        assertNull(service.plan(missing, "ARTIST_TITLE"))
        val song = fixture(); val plan = service.plan(song, "ARTIST_TITLE")!!
        val catalog = CatalogEnrichmentService.get(context)
        try { catalog.setActiveSong(song.id); assertNull(service.plan(song, "ARTIST_TITLE")); assertEquals("BUSY", service.rename(plan).state) }
        finally { catalog.setActiveSong(null) }
        assertTrue(File(song.path!!).exists())
    }
    @Test fun manualGenreTeachingKeepsAllFourSignalsWhenDisposableFeaturesAreRemoved() = runBlocking {
        val song = fixture(); val ledger = ListeningLedger.get(context)
        val audio = AudioAnalysisWorker.analyze(context, song, userInitiated = true)
        ledger.saveFeature(song.uri.toString(), "${song.dateModified}:${song.duration}", audio)
        AutomaticLyricsStore.get(context).preserveForRename(song, LyricsData(plainLyrics = "사랑 노래 우리 오늘 함께\n밤하늘 별빛 마음을 전해", syncedLyrics = null, source = "local"))
        val corrections = GenreCorrections(context); corrections.put(song, "pop")
        val saved = corrections.read().single { it.songKey == song.uri.toString() }.signals!!
        assertTrue(saved.waveform.isNotEmpty()); assertEquals(96, saved.fingerprint.size)
        assertTrue(saved.lyrics.isNotEmpty()); assertTrue(saved.title.isNotEmpty())
        ledger.writableDatabase.delete("features", "song_key=?", arrayOf(song.uri.toString()))
        EnrichmentLearningIndex(context).use { it.writableDatabase.delete("samples", "song=?", arrayOf(song.uri.toString())) }
        val rebuilt = corrections.modelExamples().second.single { it.key == song.uri.toString() }
        assertEquals(saved, rebuilt.signals); assertEquals("POP", rebuilt.genre)
    }
    @Test fun filenameSettingsStayOptInAndSearchableWithTrainingToolsHidden() {
        settings.setLearningUiEnabled(false)
        val routes = buildSettingsSearchIndex(context).associateBy { it.id }
        assertEquals(SettingsRoutes.FILE_NAMES, routes.getValue("file_names").route)
        assertEquals(SettingsRoutes.LEARNING_POWER, routes.getValue("learning_power").route)
        compose.setContent { CompositionLocalProvider(LocalContext provides compose.activity) { MaterialTheme { FileNameSettings {} } } }
        compose.onNodeWithTag("file-names-auto").assertIsOff().performClick()
        assertTrue(settings.autoFileNames.value); capture("file-name-settings.png")
        compose.onNodeWithTag("file-names-auto").performClick(); assertFalse(settings.autoFileNames.value)
    }
    @Test fun previewDoesNotRenameUntilTheSelectedChangesAreApplied() = runBlocking {
        val song = fixture(); val repository = LocalMusicModule.provideMusicRepository(context)
        repository.clearInMemoryCaches(); repository.loadSongs(forceRefresh = true, minimumDuration = 0)
        compose.setContent { CompositionLocalProvider(LocalContext provides compose.activity) { MaterialTheme { FileNameSettings {} } } }
        compose.onNodeWithTag("file-names-preview").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("file-names-apply").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("file-names-apply").performScrollTo()
        compose.onNodeWithText("→ Test Artist - Test Song.wav").assertIsDisplayed()
        assertTrue(File(song.path!!).isFile); capture("file-name-preview.png")
        compose.onNodeWithTag("file-names-apply").performClick()
        compose.waitUntil(20_000) { !File(song.path!!).exists() }
        assertEquals(song.uri, Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id))
    }
}
