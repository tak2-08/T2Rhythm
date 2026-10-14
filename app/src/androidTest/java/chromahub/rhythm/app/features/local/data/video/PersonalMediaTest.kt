// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.video

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.features.local.data.experience.*
import chromahub.rhythm.app.util.ArtworkStorage
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PersonalMediaTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated(directory: File) = object : ContextWrapper(context) { override fun getFilesDir(): File = directory }
    @Test fun artworkIsActualWebPWithTransparentPixelsAndBoundedDimensions() {
        val bitmap = Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0x80ff0000.toInt())
        val file = File.createTempFile("artwork-", ".webp", context.cacheDir)
        try {
            ArtworkStorage.write(bitmap, file)
            val data = file.readBytes(); assertEquals("RIFF", String(data.copyOfRange(0, 4))); assertEquals("WEBP", String(data.copyOfRange(8, 12)))
            val embedded = ArtworkStorage.forEmbedding(data)
            assertEquals("image/png", embedded.second); assertEquals("image/png", ArtworkStorage.mime(embedded.first))
            val decoded = BitmapFactory.decodeFile(file.path)
            assertEquals(1600, decoded.width); assertEquals(800, decoded.height); assertTrue(decoded.hasAlpha()); decoded.recycle()
        } finally { bitmap.recycle(); file.delete() }
    }
    @Test fun localVideoImportUsesRealContainersDropsAudioAndPreservesLinks() = runBlocking {
        val directory = File(context.cacheDir, "video-test-${System.nanoTime()}").apply { mkdirs() }
        val repo = MusicVideoRepository(isolated(directory))
        try {
            for (extension in listOf("mp4", "webm")) {
                val source = File(directory, "source.$extension")
                InstrumentationRegistry.getInstrumentation().context.assets.open("video-test.$extension").use { input -> source.outputStream().use { input.copyTo(it) } }
                val imported = repo.import("test:$extension", Uri.fromFile(source), "Test")
                assertTrue(imported.fileName!!.endsWith(".$extension")); assertEquals(imported, repo.get("test:$extension"))
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(requireNotNull(repo.uri(imported)).path!!)
                    assertEquals(1, extractor.trackCount)
                    assertTrue(extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME)!!.startsWith("video/"))
                } finally { extractor.release() }
                val duplicate = repo.import("second:$extension", Uri.fromFile(source), "Test")
                assertEquals(imported.fileName, duplicate.fileName)
                assertTrue(imported.bytes < source.length())
                repo.remove("test:$extension"); assertNull(repo.get("test:$extension")); assertNotNull(repo.get("second:$extension"))
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun recommendationJournalRebuildsTheSameModelAndIgnoresDuplicateEvents() {
        val directory = File(context.cacheDir, "learning-test-${System.nanoTime()}").apply { mkdirs() }
        ListeningLedger.get(context)
        val constructor = RecommendationLearning::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val learning = constructor.newInstance(isolated(directory))
        try {
            val feature = List(13) { if (it == 8) 1.0 else 0.0 }
            learning.offer(listOf(RecommendationScore("song", 1.0, listOf("place"), feature)))
            learning.feedback("song", 1.0)
            val model = learning.model(); assertEquals(1, model.samples); assertEquals(1.0, model.explicit["song"])
            val file = File(directory, "music_metadata/recommendations").listFiles()!!.single()
            file.appendText(file.readText()) // same event ID, not a second learning outcome
            val restored = constructor.newInstance(isolated(directory)).model()
            assertEquals(model, restored)
            learning.feedback("song", 0.0); assertEquals(0.0, learning.model().explicit["song"])
            learning.feedback("song", null); assertFalse(learning.model().explicit.containsKey("song"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun updaterVerifiesTheInstalledPackageAndRejectsWrongVersionAndPackage() {
        val own = File(context.applicationInfo.sourceDir)
        chromahub.rhythm.app.util.UpdateIntegrity.verifyPackage(context, own, chromahub.rhythm.app.BuildConfig.VERSION_CODE)
        assertTrue(runCatching { chromahub.rhythm.app.util.UpdateIntegrity.verifyPackage(context, own, chromahub.rhythm.app.BuildConfig.VERSION_CODE + 1) }.isFailure)
        val system = File(context.packageManager.getApplicationInfo("com.android.settings", 0).sourceDir)
        assertTrue(runCatching { chromahub.rhythm.app.util.UpdateIntegrity.verifyPackage(context, system) }.isFailure)
    }
    @Test fun locationOffProducesNoCoordinatesEvenIfPermissionExists() {
        val preferences = context.getSharedPreferences("listening_context", Context.MODE_PRIVATE)
        val original = preferences.all
        val collector = ListeningContext.get(context)
        try {
            collector.configure(enabled = false, precise = false)
            val snapshot = collector.snapshot()
            assertNull(snapshot.latitude); assertNull(snapshot.longitude); assertNull(snapshot.speedMps); assertFalse(snapshot.precise)
            assertTrue(snapshot.systemVolumeMax >= 0)
        } finally {
            preferences.edit().clear().apply {
                original.forEach { (key, value) -> when (value) { is Boolean -> putBoolean(key, value); is String -> putString(key, value); is Long -> putLong(key, value); is Float -> putFloat(key, value); is Int -> putInt(key, value) } }
            }.commit()
        }
    }
}
