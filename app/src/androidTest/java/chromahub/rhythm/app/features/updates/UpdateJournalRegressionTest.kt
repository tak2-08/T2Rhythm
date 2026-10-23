// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.updates

import android.content.Context
import android.os.Environment
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.BuildConfig
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class UpdateJournalRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "update-test-${UUID.randomUUID()}"
    private val fixtures = mutableListOf<File>()
    private val journalFile get() = File(context.noBackupFilesDir, "$namespace.json")
    @Before fun finishStartupMaintenance() {
        UpdateMaintenanceWorker.enqueue(context)
        val work = androidx.work.WorkManager.getInstance(context)
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val requests = work.getWorkInfosForUniqueWork("update-apk-maintenance").get()
            if (requests.isNotEmpty() && requests.all { it.state.isFinished }) return
            android.os.SystemClock.sleep(50)
        }
        error("Startup maintenance did not finish")
    }
    @After fun cleanup() { fixtures.forEach { it.delete() }; journalFile.delete(); File(journalFile.path + ".bak").delete() }
    private fun managed(name: String = "pending"): File = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "verified-updates/$namespace-$name.apk")
        .also { it.parentFile!!.mkdirs(); fixtures += it }
    private fun seed(file: File, code: Long, time: Long, stage: UpdateStage = UpdateStage.INSTALL_REQUESTED) {
        val record = JSONObject().put("id", namespace).put("from", "previous").put("fromCode", code - 1).put("version", "test-version")
            .put("code", code).put("time", time).put("stage", stage.name).put("file", file.absolutePath).put("bytes", file.length()).put("notes", "Saved notes")
        journalFile.writeText(JSONObject().put("records", JSONArray().put(record)).put("seenCode", code - 1).put("seenName", "previous").toString())
    }
    @Test fun observesInstallationOnceAndClearingHistoryDoesNotInventItAgain() {
        val journal = UpdateJournal.isolated(context, namespace)
        journal.reconcile(); journal.reconcile()
        assertEquals(1, journal.records.value.size); assertEquals(UpdateStage.INSTALLED, journal.records.value.single().stage)
        journal.clear(); journal.reconcile(); assertTrue(journal.records.value.isEmpty())
    }
    @Test fun confirmedInstalledApkIsDeletedAndDownloadPreferenceRetired() {
        val file = managed(); File(context.applicationInfo.sourceDir).copyTo(file, overwrite = true)
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        seed(file, BuildConfig.VERSION_CODE.toLong(), installed.lastUpdateTime - 1000)
        val prefs = context.getSharedPreferences("app_updater_downloads", Context.MODE_PRIVATE)
        prefs.edit().putString("downloaded_file", file.path).putLong("verified_size", file.length()).commit()
        val journal = UpdateJournal.isolated(context, namespace)
        val freed = journal.reconcile()
        assertTrue(freed > 0); assertFalse(file.exists()); assertNull(prefs.getString("downloaded_file", null))
        val record = journal.records.value.first { it.id == namespace }
        assertEquals(UpdateStage.INSTALLED, record.stage); assertTrue(record.apkRemoved); assertEquals("Saved notes", record.notes)
        assertTrue(UpdateJournal.isolated(context, namespace).records.value.any { it.id == namespace && it.apkRemoved })
    }
    @Test fun futureOrInterruptedInstallerKeepsItsFileAndPendingRecord() {
        val file = managed(); file.writeBytes(byteArrayOf(80, 75, 3, 4, 1))
        seed(file, BuildConfig.VERSION_CODE.toLong() + 1, System.currentTimeMillis())
        val journal = UpdateJournal.isolated(context, namespace); journal.reconcile()
        assertTrue(file.exists()); assertEquals(UpdateStage.INSTALL_REQUESTED, journal.records.value.first { it.id == namespace }.stage)
        journal.clear(); journal.reconcile(); assertTrue(file.exists()); assertTrue(journal.records.value.isEmpty())
    }
    @Test fun externalFilesAndSymlinksCannotBecomeManagedInstallationFiles() {
        val outside = File(context.cacheDir, "$namespace.apk").also { it.writeText("outside"); fixtures += it }
        val journal = UpdateJournal.isolated(context, namespace)
        try { journal.ready(outside); fail("Files outside the updater directory are not owned") } catch (_: IllegalArgumentException) { }
        val link = File(context.filesDir, "verified-updates/$namespace-link.apk").also { it.parentFile!!.mkdirs(); fixtures += it }; Files.createSymbolicLink(link.toPath(), outside.toPath())
        try { journal.ready(link); fail("A symlink must not be enrolled") } catch (_: IllegalArgumentException) { }
        journal.reconcile(); assertEquals("outside", outside.readText()); assertTrue(Files.isSymbolicLink(link.toPath()))
    }
    @Test fun oldUntrackedInstalledApkIsCleanedWithoutScanningUserDownloads() {
        val file = managed("legacy"); File(context.applicationInfo.sourceDir).copyTo(file, overwrite = true)
        val unrelated = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "$namespace-user.apk").also { it.writeText("leave alone"); fixtures += it }
        val journal = UpdateJournal.isolated(context, namespace); journal.reconcile()
        assertFalse(file.exists()); assertEquals("leave alone", unrelated.readText())
    }
    @Test fun historyPersistsAndRemainsBoundedAcrossReloads() {
        val records = JSONArray()
        repeat(125) { i -> records.put(JSONObject().put("id", "record-$i").put("version", "v$i").put("code", i + 1)
            .put("time", 1000 - i).put("stage", "INSTALLED")) }
        journalFile.writeText(JSONObject().put("records", records).toString())
        val journal = UpdateJournal.isolated(context, namespace); assertEquals(100, journal.records.value.size)
        journal.remove("record-0"); assertEquals(99, UpdateJournal.isolated(context, namespace).records.value.size)
    }
}
