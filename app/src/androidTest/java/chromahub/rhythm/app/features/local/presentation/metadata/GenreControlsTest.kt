// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.discovery.*
import chromahub.rhythm.app.features.local.data.experience.*
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.*
import chromahub.rhythm.app.shared.presentation.screens.settings.*
import chromahub.rhythm.app.util.GenreUtils
import java.io.File
import java.util.Locale
import java.util.UUID
import org.junit.Assert.*
import org.junit.*

class GenreControlsTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "genre-control-${UUID.randomUUID()}"
    private val backups = mutableMapOf<File, ByteArray?>()
    private var previousUi = true
    private var previousShelf: String? = null
    private fun song(id: String, genre: String?) = Song("$prefix-$id", "장르 학습용 $id", "검사용 가수", "앨범",
        duration = 180_000, uri = Uri.parse("file:///test/$prefix/$id.mp3"), genre = genre)
    @Before @Suppress("DEPRECATION") fun setup() {
        compose.runOnUiThread {
            Locale.setDefault(Locale.KOREAN)
            listOf(base.resources, compose.activity.resources).forEach { r ->
                r.updateConfiguration(Configuration(r.configuration).apply { setLocale(Locale.KOREAN) }, r.displayMetrics)
            }
            previousUi = AppSettings.getInstance(base).learningUiEnabled.value
            AppSettings.getInstance(base).setLearningUiEnabled(true)
        }
        listOf(File(base.filesDir, "music_metadata/personalization/learning/genre-corrections.json"),
            File(base.cacheDir, "enrichment-model.json")).forEach { backups[it] = it.takeIf(File::exists)?.readBytes() }
        previousShelf = base.getSharedPreferences("recommendation_shelf", 0).getString("current", null)
    }
    @After fun cleanup() {
        backups.forEach { (file, bytes) -> if (bytes == null) file.delete() else file.writeBytes(bytes) }
        GenreCorrections.revision.value++; EnrichmentStore.revision.value++
        EnrichmentLearningIndex(base).use { it.writableDatabase.delete("samples", "song LIKE ?", arrayOf("%$prefix%")) }
        ListeningLedger.get(base).writableDatabase.delete("features", "song_key LIKE ?", arrayOf("%$prefix%"))
        base.getSharedPreferences("recommendation_shelf", 0).edit().putString("current", previousShelf).commit()
        compose.runOnUiThread { AppSettings.getInstance(base).setLearningUiEnabled(previousUi) }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            File(base.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun multiGenreMixContainsBothGenresAndRetainsItsFilter() {
        val songs = listOf(song("pop", "Pop"), song("rock", "Rock"), song("jazz", "Jazz"), song("missing", "-"))
        val queue = PersonalCollections.generate(base, songs, songs.map { it.id }, null, PersonalCollection.DAILY_MIX, genre = "Pop, Rock", seed = 27)
        assertEquals(setOf("Pop", "Rock"), queue.map { it.genre }.toSet())
        val snapshot = RecommendationShelf.create(base, PersonalCollection.DAILY_MIX, "Pop, Rock", queue.map { it.uri.toString() }, 27)!!
        assertEquals(setOf("pop", "rock"), GenreUtils.normalizedGenres(RecommendationShelf.current(base)!!.genre))
        val refreshed = PersonalCollections.generate(base, songs, emptyList(), null, snapshot.mode, genre = snapshot.genre, seed = 28)
        assertEquals(queue.map { it.id }.toSet(), refreshed.map { it.id }.toSet())
    }
    @Test fun correctionOverridesRecommendationsAndCanBeRemovedWithoutEditingOriginalTag() {
        val source = song("manual", "-")
        val store = GenreCorrections(base)
        store.put(source, "Jazz")
        val result = PersonalCollections.generate(base, listOf(source), emptyList(), null, PersonalCollection.DAYLIST, genre = "Jazz")
        assertEquals(listOf(source.id), result.map { it.id })
        assertEquals("-", source.genre)
        store.put(source, "Rock")
        assertEquals(1, store.read().count { it.songKey == source.uri.toString() })
        assertEquals("Rock", GenreCorrections(base).apply(listOf(source)).single().genre)
        store.remove(source.uri.toString()); store.refreshModel(listOf(source), source)
        assertEquals("-", store.apply(listOf(source)).single().genre)
    }
    @Test fun analyzedUserCorrectionsReplaceOldLabelsInTheRealModel() {
        val source = song("audio", "Pop")
        val signature = "${source.dateModified}:${source.duration}"
        val feature = AudioFeatures(songKey = source.uri.toString(), pcmSha256 = prefix, sampleRate = 16000, channels = 1,
            decodedDurationMs = source.duration, rmsDb = -12.0, peakDb = -3.0, zeroCrossingRate = .2,
            spectralBands = List(8) { .125 }, waveform = List(32) { .3 }, sourceSignature = signature)
        ListeningLedger.get(base).saveFeature(source.uri.toString(), signature, feature)
        val store = GenreCorrections(base)
        store.put(source, "Jazz"); store.refreshModel(listOf(source), source)
        var examples = EnrichmentStore(base).model().examples.filter { it.key == source.uri.toString() }
        assertEquals(listOf("jazz"), examples.map { it.genre })
        store.put(source, "Rock"); store.refreshModel(listOf(source), source)
        examples = EnrichmentStore(base).model().examples.filter { it.key == source.uri.toString() }
        assertEquals(listOf("rock"), examples.map { it.genre })
        EnrichmentStore(base).saveModel(EnrichmentModel(examples = listOf(GenreExample(source.uri.toString(), "pop", listOf(1.0, 0.0)))))
        assertEquals(listOf("rock"), EnrichmentStore(base).model().examples.filter { it.key == source.uri.toString() }.map { it.genre })
    }
    @Test fun legacyPlaceholderSamplesAreFilteredFromIndexAndCachedModel() {
        EnrichmentLearningIndex(base).use { index ->
            index.put("$prefix-empty", "-", listOf(1.0, 0.0), null, "v1")
            index.writableDatabase.execSQL("UPDATE samples SET genre='-' WHERE song=?", arrayOf("$prefix-empty"))
            assertTrue(index.model(emptyList()).examples.none { it.key == "$prefix-empty" })
        }
        val file = File(base.cacheDir, "enrichment-model.json")
        file.writeText(com.google.gson.Gson().toJson(EnrichmentModel(examples = listOf(GenreExample("$prefix-old", "—", listOf(1.0, 0.0))))))
        assertTrue(EnrichmentStore(base).model().examples.isEmpty())
    }
    @Test fun pickerChecksMultipleGenresAndOnlyAppliesOnConfirmation() {
        var applied: Set<String>? = null
        compose.setContent { MaterialTheme {
            GenreSelectionDialog(listOf("Pop", "Rock", "Jazz", "-"), emptySet(), onDismiss = {}, onApply = { applied = it })
        } }
        compose.onNodeWithText("-").assertDoesNotExist()
        compose.onNodeWithText("Pop").performClick()
        compose.onNodeWithText("Rock").performClick()
        assertNull(applied)
        capture("genre-multiple-selection.png")
        compose.onNodeWithText(base.getString(R.string.genre_apply)).performClick()
        assertEquals(setOf("Pop", "Rock"), applied)
    }
    @Test fun teachingScreenFindsSongAndSavesUserGenre() {
        val source = song("화면", "-")
        compose.setContent { CompositionLocalProvider(LocalContext provides compose.activity) {
            MaterialTheme { GenreTrainingPanel(loadSongs = { listOf(source) }, scheduleTraining = {}) }
        } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText(source.title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(source.title).performClick()
        compose.onNodeWithText(base.getString(R.string.genre_correct_label)).performTextInput("Jazz, Soul")
        Espresso.closeSoftKeyboard()
        capture("genre-manual-teaching.png")
        compose.onNodeWithText(base.getString(R.string.genre_teach)).performClick()
        compose.waitUntil(15_000) { GenreCorrections(base).read().any { it.songKey == source.uri.toString() && it.genre == "Jazz, Soul" } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText(base.getString(R.string.genre_saved)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("-", source.genre)
    }
    @Test fun visibilitySwitchHidesTrainingEntryAndKeepsAlgorithmServiceAndData() {
        val source = song("유지", "Pop"); GenreCorrections(base).put(source, "Jazz")
        compose.setContent { CompositionLocalProvider(LocalContext provides compose.activity) { MaterialTheme {
            SettingsScreen(onBackClick = {}, onNavigateTo = { error("Unexpected route $it") })
        } } }
        compose.onNode(hasSetTextAction()).performTextInput("학습 UI")
        compose.onNodeWithText(base.getString(R.string.learning_ui_title)).performClick()
        compose.onNode(isToggleable()).performClick()
        assertFalse(AppSettings.getInstance(base).learningUiEnabled.value)
        compose.onNodeWithText(base.getString(android.R.string.ok)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("알고리즘")
        compose.onNodeWithText(base.getString(R.string.algorithm_training_title)).assertDoesNotExist()
        compose.onNodeWithText(base.getString(R.string.algorithm_title)).performClick()
        compose.onNodeWithText(base.getString(R.string.algorithm_patterns)).assertIsDisplayed()
        compose.onNodeWithText(base.getString(R.string.algorithm_learning)).assertDoesNotExist()
        capture("genre-service-with-training-hidden.png")
        assertEquals("Jazz", GenreCorrections(base).apply(listOf(source)).single().genre)
        assertFalse(base.getSharedPreferences("rhythm_preferences", 0).getBoolean("learning_ui_enabled", true))
        compose.onNodeWithText(base.getString(R.string.ui_close)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(base.getString(R.string.algorithm_patterns)).fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasSetTextAction()).performTextReplacement("학습 UI")
        compose.onNodeWithText(base.getString(R.string.learning_ui_title)).performClick()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText(base.getString(android.R.string.ok)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("알고리즘")
        compose.onNodeWithText(base.getString(R.string.algorithm_training_title)).assertIsDisplayed()
        assertEquals("Jazz", GenreCorrections(base).apply(listOf(source)).single().genre)
    }
    @Test fun hidingTrainingToolsKeepsRecommendationPlaybackAndLyricsContent() {
        val songs = listOf(song("한곡", "Pop"), song("다른곡", "Rock"))
        compose.runOnUiThread { AppSettings.getInstance(base).setLearningUiEnabled(false) }
        compose.setContent { CompositionLocalProvider(LocalContext provides compose.activity) { MaterialTheme {
            val vm: MusicViewModel = viewModel()
            Column { PersonalCollectionsContent(songs, emptyList(), null, { _, _ -> }, { _, _ -> })
                Text("일반 가사 본문")
                LyricSuggestionButton(songs.first(), LyricsData(plainLyrics = "첫 줄\n둘째 줄\n셋째 줄", syncedLyrics = null), vm)
            }
        } } }
        compose.onNodeWithText(base.getString(R.string.personal_like)).assertDoesNotExist()
        compose.onNodeWithText(base.getString(R.string.personal_dislike)).assertDoesNotExist()
        compose.onNodeWithTag("lyric-manual-action").assertDoesNotExist()
        compose.onNodeWithTag("lyric-ai-action").assertDoesNotExist()
        compose.onNodeWithContentDescription(base.getString(R.string.action_play)).assertExists()
        compose.onNodeWithText("일반 가사 본문").assertIsDisplayed()
    }
}
