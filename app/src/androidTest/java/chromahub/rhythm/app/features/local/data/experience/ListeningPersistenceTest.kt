// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.net.Uri
import chromahub.rhythm.app.shared.data.model.Song
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class ListeningPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun row(id: String, duration: Long = 20_000, recorded: Long = 1000) = ListeningRecord(id, id, "segment", id, id,
        "Test", "Test artist", "Test album", "Pop", 100_000, 100_000 + duration, duration, 60_000, 0, duration, 1f, "checkpoint", recordedAt = recorded)
    @Test fun checkpointUpdatesOneRecordAndOldImportCannotReplaceIt() {
        val ledger = ListeningLedger.get(context); val id = UUID.randomUUID().toString()
        ledger.record(row(id)); ledger.record(row(id, 30_000, 2000))
        assertEquals(30_000L, ledger.records().single { it.id == id }.listenedMs)
        val input = File.createTempFile("old-checkpoint", ".jsonl", context.cacheDir)
        try { input.writeText(Gson().toJson(row(id)) + "\n"); ledger.importJournal(input) }
        finally { input.delete() }
        assertEquals(30_000L, ledger.records().single { it.id == id }.listenedMs)
    }
    @Test fun rawJournalRecoversARecordMissingFromTheDatabase() {
        val ledger = ListeningLedger.get(context); val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "listening_history").apply { mkdirs() }
        val journal = File(directory, "1970-01-recovery-test.jsonl")
        journal.appendText(Gson().toJson(row(id)) + "\n")
        ledger.recover()
        assertNotNull(ledger.records().firstOrNull { it.id == id })
        ledger.recover()
        assertEquals(1, ledger.records().count { it.id == id })
    }
    @Test fun fullTrackPcmAnalysisProducesAnActualFingerprintAndWaveform() = runBlocking {
        val samples = 8192; val data = ByteBuffer.allocate(samples * 2 + 44).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(samples * 2 + 36).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8192).putInt(16384).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { n -> data.putShort((16384 * sin(2 * PI * 1000 * n / 8192)).toInt().toShort()) }
        val file = File.createTempFile("audio-features-", ".wav", context.cacheDir)
        try {
            file.writeBytes(data.array())
            val song = Song("audio-test", "Tone", "Test", "Test", duration = 1000, uri = Uri.fromFile(file))
            val features = AudioAnalysisWorker.analyze(context, song)
            assertEquals(64, features.pcmSha256.length); assertEquals(1000L, features.decodedDurationMs)
            assertEquals(256, features.waveform.size); assertTrue(features.waveform.max() > .3)
            assertTrue(features.rmsDb in -10.0..-8.0)
        } finally { file.delete() }
    }
}
