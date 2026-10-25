// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.ai

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.shared.data.model.Song
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AiSafetyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun credentialsAreEncryptedInNoBackupStorageAndSelectionPersists() {
        val namespace = "ai_test_${UUID.randomUUID()}"
        val store = AiStore(context, namespace)
        val directory = File(context.noBackupFilesDir, namespace)
        try {
            assertFalse(store.read().available)
            val key = "test-only-${UUID.randomUUID()}"
            store.save(AiSettings(true, listOf(AiConnection("first", AiProvider.ANTHROPIC, "model", key)), true, false))
            val bytes = File(directory, "connections.enc").readBytes()
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(key))
            val loaded = store.read()
            assertTrue(loaded.available); assertTrue(loaded.rotate); assertFalse(loaded.fallback)
            assertEquals(key, loaded.connections.single().key)
            assertTrue(File(directory, "available").isFile)
            store.save(AiSettings(false, loaded.connections, true, false))
            assertFalse(File(directory, "available").exists())
            assertFalse(store.read().available)
            assertFalse(File(context.filesDir, namespace).exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun durableBeforeImageDoesNotTouchTheAudioAndDetectsChangedTargets() {
        val source = File(context.cacheDir, "ai-journal-${UUID.randomUUID()}.wav").apply { writeBytes(byteArrayOf(1, 3, 5, 7)) }
        val before = Song("ai-${UUID.randomUUID()}", "original", "artist", "album", duration = 1000, uri = Uri.fromFile(source), genre = "Jazz")
        val after = before.copy(title = "reviewed", genre = "Rock")
        val journal = AiLibraryEdits(context)
        val entry = journal.prepare(before, after)
        try {
            val reopened = AiLibraryEdits(context).entries().single { it.id == entry.id }
            assertTrue(AiLibraryEdits.sameTags(before, reopened.before))
            assertTrue(AiLibraryEdits.sameTags(after, reopened.after))
            assertFalse(AiLibraryEdits.sameTags(after.copy(title = "later manual edit"), reopened.after))
            assertFalse(AiLibraryEdits.sameTags(after.copy(uri = Uri.parse("content://media/external/audio/media/999")), reopened.after))
            assertArrayEquals(byteArrayOf(1, 3, 5, 7), source.readBytes())
        } finally { journal.forget(entry); source.delete() }
    }
}
