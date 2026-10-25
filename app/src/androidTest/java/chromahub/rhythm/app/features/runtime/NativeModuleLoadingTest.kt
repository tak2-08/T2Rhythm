// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.runtime

import android.graphics.Bitmap
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.runtime.HostEnvironment
import chromahub.rhythm.runtime.ModuleActivationPolicy
import chromahub.rhythm.runtime.ModuleActivationState
import chromahub.rhythm.runtime.ModuleRejected
import chromahub.rhythm.runtime.Rejection
import chromahub.rhythm.runtime.RuntimePackageVerifier
import chromahub.rhythm.runtime.StartupDecision
import chromahub.rhythm.runtime.StartupSafety
import chromahub.rhythm.runtime.VerifiedModule
import chromahub.rhythm.runtime.api.NativeUiModule
import chromahub.rhythm.runtime.host.NativeModuleTrialLoader
import chromahub.rhythm.runtime.host.ModuleStateStore
import chromahub.rhythm.runtime.host.CorruptModuleState
import chromahub.rhythm.runtime.host.ModuleResourceContext
import com.google.gson.Gson
import dalvik.system.DexClassLoader
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyPairGenerator
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Real DEX/Compose probe; does not assert that the complete music app is modularized. */
class NativeModuleLoadingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val verifier = RuntimePackageVerifier(listOf(key.public))
    private val host = HostEnvironment("t2rhythm.main", chromahub.rhythm.app.BuildConfig.VERSION_CODE.toLong(),
        1, "c".repeat(64), android.os.Build.VERSION.SDK_INT, 1)
    private val source = File(context.noBackupFilesDir, "runtime-test-${UUID.randomUUID()}")
    private val sharedLoader = NativeUiModule::class.java.classLoader!!
    private val safe = StartupSafety(true, true, false, false, false)
    private val packageHashes = mutableListOf<String>()

    @Before fun prepare() {
        source.mkdirs()
        System.clearProperty(MARKER)
    }

    @After fun cleanup() {
        source.deleteRecursively()
        packageHashes.forEach { File(context.noBackupFilesDir, "runtime-trial/$it").deleteRecursively() }
        System.clearProperty(MARKER)
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun makeArchive(version: Long, tamperPayload: Boolean = false, writable: Boolean = false,
                            payloadBytes: ByteArray? = null, entryPoint: String = ENTRY,
                            signer: KeyPair = key, environment: HostEnvironment = host): File {
        val payload = payloadBytes ?: instrumentation.context.assets.open("content.dex.jar").use { it.readBytes() }
        val notes = "Native runtime probe $version".toByteArray()
        val manifest = Gson().toJson(linkedMapOf(
            "formatVersion" to 1,
            "moduleId" to environment.moduleId,
            "versionCode" to version,
            "versionName" to "probe-$version-${UUID.randomUUID()}",
            "signingKeyId" to RuntimePackageVerifier.keyId(signer.public),
            "entryPointClass" to entryPoint,
            "hostApi" to environment.hostApi,
            "runtimeFingerprint" to environment.runtimeFingerprint,
            "minBaseVersion" to environment.baseVersion,
            "maxBaseVersion" to environment.baseVersion,
            "minSdk" to 26,
            "maxSdk" to 37,
            "minDataSchema" to 1,
            "maxDataSchema" to 1,
            "payloadSize" to payload.size,
            "payloadSha256" to hash(payload),
            "notesSize" to notes.size,
            "notesSha256" to hash(notes),
        )).toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(signer.private); update(manifest); sign()
        }
        val file = File(source, "${UUID.randomUUID()}.t2module")
        ZipOutputStream(file.outputStream()).use { zip ->
            linkedMapOf(
                "manifest.json" to manifest,
                "manifest.sig" to signature,
                "content.dex.jar" to if (tamperPayload) payload + 1.toByte() else payload,
                "notes.md" to notes,
            ).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        if (!writable) assertTrue(file.setReadOnly())
        packageHashes += hash(file.readBytes())
        return file
    }

    private fun activation(archive: File): StartupDecision.Activate {
        val current = verifier.verify(makeArchive(1), host)
        val verified = verifier.verify(archive, host)
        val staged = ModuleActivationPolicy.stage(ModuleActivationState(current.reference), verified)
        val approved = ModuleActivationPolicy.approve(staged, verified.archiveSha256)
        return ModuleActivationPolicy.prepareStartup(approved, host, safe) as StartupDecision.Activate
    }

    private fun loader(environment: HostEnvironment = host) = NativeModuleTrialLoader(context, verifier, environment, sharedLoader)

    @Test fun rotatedBaseKeyReconcilesPersistedJournalAndDropsOldExecutableReferences() {
        val old = verifier.verify(makeArchive(1), host)
        val candidate = verifier.verify(makeArchive(2), host)
        val archives = source.listFiles()!!.associateBy { hash(it.readBytes()) }.toMutableMap()
        val directory = File(source, "key-rotation-journal")
        ModuleStateStore(context, directory, old) { verifier.verifyAuthenticity(archives.getValue(it)) }.stage(candidate)

        val rotated = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val current = RuntimePackageVerifier(listOf(rotated.public))
        val journal = RuntimePackageVerifier(listOf(key.public, rotated.public))
        val newHost = host.copy(runtimeFingerprint = "d".repeat(64))
        val embeddedFile = makeArchive(3, signer = rotated, environment = newHost)
        val embedded = current.verify(embeddedFile, newHost)
        archives[embedded.archiveSha256] = embeddedFile
        val store = ModuleStateStore(context, directory, embedded) { journal.verifyAuthenticity(archives.getValue(it)) }
        val decision = store.prepareStartup(newHost, safe)
        assertTrue(decision is StartupDecision.Keep)
        assertEquals(embedded.reference, decision.state.active)
        assertNull(decision.state.previousGood)
        assertNull(decision.state.candidate)
        assertEquals(3L, decision.state.highestApprovedVersion)
        // The reconciled journal no longer needs the historical verifier, even after reopening.
        val reopened = ModuleStateStore(context, directory, embedded) { current.verifyAuthenticity(archives.getValue(it)) }
        assertEquals(decision.state, reopened.snapshot())
        assertEquals(Rejection.UNKNOWN_SIGNING_KEY, assertThrows(ModuleRejected::class.java) {
            current.verifyAuthenticity(archives.getValue(old.archiveSha256))
        }.reason)
        assertNull(System.getProperty(MARKER))
    }

    @Test fun verificationAndApprovalNeverInitializeDownloadedClasses() {
        assertThrows(ClassNotFoundException::class.java) { sharedLoader.loadClass(ENTRY) }
        val archive = makeArchive(2)
        val verified = verifier.verify(archive, host)
        assertNull(System.getProperty(MARKER))
        val staged = ModuleActivationPolicy.stage(ModuleActivationState(verifier.verify(makeArchive(1), host).reference), verified)
        assertTrue(ModuleActivationPolicy.prepareStartup(staged, host, safe) is StartupDecision.Keep)
        ModuleActivationPolicy.approve(staged, verified.archiveSha256)
        assertNull(System.getProperty(MARKER))
    }

    @Test fun approvedExternalDexRendersInteractiveNativeComposeOnReadOnlyStorage() {
        val fixture = storeFixture()
        val store = fixture.reopen()
        store.stage(fixture.candidate)
        store.approve(fixture.candidate.archiveSha256)
        val decision = store.prepareStartup(host, safe) as StartupDecision.Activate
        assertTrue(fixture.reopen().snapshot().trialInProgress)
        val loaded = loader().loadTrial(decision, fixture.candidateFile)
        assertEquals("true", System.getProperty(MARKER))
        assertTrue(loaded.entry.javaClass.classLoader is DexClassLoader)
        assertNotSame(sharedLoader, loaded.entry.javaClass.classLoader)
        assertEquals(0, Os.stat(loaded.readOnlyDex.path).st_mode and
            (OsConstants.S_IWUSR or OsConstants.S_IWGRP or OsConstants.S_IWOTH))
        compose.setContent { AndroidView(factory = { loaded.entry.createView(it) }) }
        compose.onNodeWithText("T2Rhythm native module").assertIsDisplayed()
        compose.onNodeWithText("Module clicks: 0").assertIsDisplayed().performClick()
        compose.onNodeWithText("Module clicks: 1").assertIsDisplayed()
        compose.waitForIdle()
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "native-module-api${host.sdk}.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
        store.confirmHealthy(loaded.reference.archiveSha256)
        assertFalse(fixture.reopen().snapshot().trialInProgress)
    }

    @Test fun tamperedPayloadIsRejectedBeforeClassInitialization() {
        val file = makeArchive(2, tamperPayload = true)
        val error = assertThrows(ModuleRejected::class.java) { verifier.verify(file, host) }
        assertEquals(Rejection.PAYLOAD_INTEGRITY, error.reason)
        assertNull(System.getProperty(MARKER))
    }

    @Test fun writableArchiveCannotBeLoaded() {
        val file = makeArchive(2, writable = true)
        val decision = activation(file)
        assertThrows(IllegalArgumentException::class.java) { loader().loadTrial(decision, file) }
        assertNull(System.getProperty(MARKER))
    }

    @Test fun exactApprovedArchiveIsRequiredAtLoading() {
        val decision = activation(makeArchive(2))
        val replacement = makeArchive(2)
        assertThrows(IllegalArgumentException::class.java) { loader().loadTrial(decision, replacement) }
        assertNull(System.getProperty(MARKER))
    }

    @Test fun loaderPinsOneVersionForItsProcessSession() {
        val first = makeArchive(2)
        val decision = activation(first)
        val loader = loader()
        val loaded = loader.loadTrial(decision, first)
        assertSame(loaded, loader.loadTrial(decision, first))
        val next = makeArchive(3)
        assertThrows(IllegalStateException::class.java) { loader.loadTrial(activation(next), next) }
    }

    @Test fun runtimeCompatibilityIsCheckedAgainBeforeClassInitialization() {
        val file = makeArchive(2)
        val decision = activation(file)
        val error = assertThrows(ModuleRejected::class.java) {
            loader(host.copy(runtimeFingerprint = "d".repeat(64))).loadTrial(decision, file)
        }
        assertEquals(Rejection.RUNTIME_FINGERPRINT, error.reason)
        assertNull(System.getProperty(MARKER))
    }

    @Test fun changedMaterializedDexCannotBeReused() {
        val file = makeArchive(2)
        val decision = activation(file)
        val dex = loader().loadTrial(decision, file).readOnlyDex
        assertTrue(dex.setWritable(true, true))
        dex.appendBytes(byteArrayOf(1))
        assertTrue(dex.setReadOnly())
        System.clearProperty(MARKER)
        assertThrows(IllegalArgumentException::class.java) { loader().loadTrial(decision, file) }
        assertNull(System.getProperty(MARKER))
    }

    @Test fun compiledModuleStringsAndDrawablesLoadThroughPublicApisWithoutInstallation() {
        val payload = instrumentation.context.assets.open("resource-probe.apk").use { it.readBytes() }
        val file = makeArchive(2, payloadBytes = payload, entryPoint = "chromahub.rhythm.module.ResourceProbeEntry")
        val loaded = loader().loadTrial(activation(file), file)
        val parentSymbols = sharedLoader.loadClass("chromahub.rhythm.app.R\$string")
        assertThrows(NoSuchFieldException::class.java) { parentSymbols.getDeclaredField("runtime_probe_text") }
        val featureSymbols = loaded.entry.javaClass.classLoader!!.loadClass("chromahub.rhythm.app.R\$string")
        assertNotSame(parentSymbols, featureSymbols)
        val moduleContext = ModuleResourceContext.attach(context, loaded)
        assertEquals(context.packageName, moduleContext.packageName)
        instrumentation.runOnMainSync {
            val view = loaded.entry.createView(moduleContext) as android.widget.TextView
            assertEquals("New resource from the signed module", view.text.toString())
            assertNotNull(view.compoundDrawables[0])
            assertTrue(view.compoundDrawables[0].intrinsicWidth > 0)
            val configuration = android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale.KOREAN)
            }
            val koreanContext = ModuleResourceContext.attach(context.createConfigurationContext(configuration), loaded)
            val korean = loaded.entry.createView(koreanContext) as android.widget.TextView
            assertEquals("서명된 기능 패키지의 새 리소스", korean.text.toString())
            val original = loaded.entry.createView(moduleContext) as android.widget.TextView
            assertEquals("New resource from the signed module", original.text.toString())
        }
        assertEquals(context.packageName, context.packageManager.getPackageArchiveInfo(loaded.readOnlyDex.path, 0)!!.packageName)
        assertNotEquals(loaded.readOnlyDex.path, context.packageManager.getPackageInfo(context.packageName, 0).applicationInfo!!.sourceDir)
    }

    @Test fun resourceCacheRemovesDeadCopiesAndPreservesLiveProcessLeases() {
        val cache = File(context.codeCacheDir, "runtime-resources")
        val dead = File(cache, "${hash(UUID.randomUUID().toString().toByteArray())}/dead-process")
        val live = File(cache, "${hash(UUID.randomUUID().toString().toByteArray())}/live-process")
        assertTrue(dead.mkdirs())
        assertTrue(live.mkdirs())
        File(dead, "content.apk").writeText("dead assets")
        File(live, "content.apk").writeText("live assets")
        try {
            RandomAccessFile(File(live, ".lease"), "rw").use { handle -> handle.channel.lock().use {
                val payload = instrumentation.context.assets.open("resource-probe.apk").use { it.readBytes() }
                val archive = makeArchive(2, payloadBytes = payload, entryPoint = "chromahub.rhythm.module.ResourceProbeEntry")
                val loaded = loader().loadTrial(activation(archive), archive)
                val resources = ModuleResourceContext.attach(context, loaded)
                assertFalse("Dead process resources accumulated", dead.exists())
                assertEquals("live assets", File(live, "content.apk").readText())
                instrumentation.runOnMainSync {
                    assertEquals("New resource from the signed module", (loaded.entry.createView(resources) as android.widget.TextView).text.toString())
                }
            } }
        } finally {
            dead.parentFile?.deleteRecursively()
            live.parentFile?.deleteRecursively()
        }
    }

    private data class StoreFixture(
        val reopen: () -> ModuleStateStore,
        val candidate: VerifiedModule,
        val candidateFile: File,
        val directory: File,
    )

    private fun storeFixture(): StoreFixture {
        val files = listOf(makeArchive(1), makeArchive(2))
        val verified = files.map { verifier.verify(it, host) }
        val byHash = verified.zip(files).associate { (module, file) -> module.archiveSha256 to file }
        val directory = File(source, "state")
        return StoreFixture(
            reopen = { ModuleStateStore(context, directory, verified[0]) { digest ->
                verifier.verify(byHash.getValue(digest), host)
            } },
            candidate = verified[1],
            candidateFile = files[1],
            directory = directory,
        )
    }

    @Test fun stagedUnapprovedCandidateStaysUnapprovedAfterReopeningStore() {
        val fixture = storeFixture()
        fixture.reopen().stage(fixture.candidate)
        val restored = fixture.reopen().snapshot()
        assertFalse(restored.candidate!!.approved)
        assertTrue(fixture.reopen().prepareStartup(host, safe) is StartupDecision.Keep)
        assertEquals(1L, restored.active.manifest.versionCode)
    }

    @Test fun approvalAndTrialAreDurableBeforeLoadingCode() {
        val fixture = storeFixture()
        fixture.reopen().stage(fixture.candidate)
        fixture.reopen().approve(fixture.candidate.archiveSha256)
        assertTrue(fixture.reopen().snapshot().candidate!!.approved)
        val activation = fixture.reopen().prepareStartup(host, safe)
        assertTrue(activation is StartupDecision.Activate)
        val persisted = fixture.reopen().snapshot()
        assertTrue(persisted.trialInProgress)
        assertEquals(2L, persisted.active.manifest.versionCode)
        assertEquals(1L, persisted.previousGood!!.manifest.versionCode)
        val recovered = fixture.reopen().prepareStartup(host, safe)
        assertTrue(recovered is StartupDecision.Recover)
        assertEquals(1L, fixture.reopen().snapshot().active.manifest.versionCode)
        assertEquals(2L, fixture.reopen().snapshot().highestApprovedVersion)
    }

    @Test fun healthyTrialIsNotRolledBackAfterReopeningStore() {
        val fixture = storeFixture()
        fixture.reopen().stage(fixture.candidate)
        fixture.reopen().approve(fixture.candidate.archiveSha256)
        fixture.reopen().prepareStartup(host, safe)
        fixture.reopen().confirmHealthy(fixture.candidate.archiveSha256)
        val next = fixture.reopen().prepareStartup(host, safe)
        assertTrue(next is StartupDecision.Keep)
        assertEquals(2L, next.state.active.manifest.versionCode)
        assertFalse(next.state.trialInProgress)
    }

    @Test fun interruptedAtomicWriteRetainsPreviousConfirmedState() {
        val fixture = storeFixture()
        fixture.reopen().stage(fixture.candidate)
        fixture.reopen().approve(fixture.candidate.archiveSha256)
        val atomic = AtomicFile(File(fixture.directory, ModuleStateStore.STATE_FILE_NAME))
        atomic.startWrite().use { stream ->
            stream.write(byteArrayOf(1, 2, 3))
            stream.fd.sync()
            // Simulates a writer dying before finishWrite/failWrite, then a fresh reader.
        }
        val restored = fixture.reopen().snapshot()
        assertTrue(restored.candidate!!.approved)
        assertEquals(fixture.candidate.archiveSha256, restored.candidate!!.module.archiveSha256)
    }

    @Test fun corruptedStateNeverSilentlyApprovesOrBootsDownloadedCode() {
        val fixture = storeFixture()
        fixture.reopen().stage(fixture.candidate)
        val file = File(fixture.directory, ModuleStateStore.STATE_FILE_NAME)
        file.writeBytes(file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        assertThrows(CorruptModuleState::class.java) { fixture.reopen().snapshot() }
        assertNull(System.getProperty(MARKER))
        val music = context.getSharedPreferences("runtime-recovery-preservation", android.content.Context.MODE_PRIVATE)
        music.edit().putString("playlist", "saved user music").commit()
        val restored = fixture.reopen().restoreEmbedded(host)
        assertEquals(1L, restored.active.manifest.versionCode)
        assertNull(restored.candidate)
        assertEquals(restored, fixture.reopen().snapshot())
        assertEquals("saved user music", music.getString("playlist", null))
        music.edit().clear().commit()
    }

    @Test fun unreadableExistingStateCannotBeMistakenForFirstInstallation() {
        val fixture = storeFixture()
        fixture.reopen().stage(fixture.candidate)
        val file = File(fixture.directory, ModuleStateStore.STATE_FILE_NAME)
        Os.chmod(file.path, 0)
        try {
            assertThrows(CorruptModuleState::class.java) { fixture.reopen().snapshot() }
            assertNull(System.getProperty(MARKER))
        } finally {
            Os.chmod(file.path, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        }
    }

    @Test fun concurrentStoreInstancesSerializeApprovalsWithoutCorruption() {
        val fixture = storeFixture()
        fixture.reopen().stage(fixture.candidate)
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val futures = (1..8).map { executor.submit {
                start.await()
                fixture.reopen().approve(fixture.candidate.archiveSha256)
            } }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
            val state = fixture.reopen().snapshot()
            assertTrue(state.candidate!!.approved)
            assertEquals(2L, state.highestApprovedVersion)
            assertEquals(1L, state.active.manifest.versionCode)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun wrongArchiveApprovalCannotChangePersistedCandidate() {
        val fixture = storeFixture()
        fixture.reopen().stage(fixture.candidate)
        assertThrows(IllegalArgumentException::class.java) { fixture.reopen().approve("f".repeat(64)) }
        assertFalse(fixture.reopen().snapshot().candidate!!.approved)
    }

    @Test fun cancelledCandidateIsRemovedDurably() {
        val fixture = storeFixture()
        fixture.reopen().stage(fixture.candidate)
        fixture.reopen().approve(fixture.candidate.archiveSha256)
        fixture.reopen().discardCandidate()
        assertNull(fixture.reopen().snapshot().candidate)
        assertEquals(1L, fixture.reopen().snapshot().active.manifest.versionCode)
        assertTrue(fixture.reopen().prepareStartup(host, safe) is StartupDecision.Keep)
    }

    companion object {
        private const val MARKER = "t2rhythm.runtime.probe.initialized"
        private const val ENTRY = "chromahub.rhythm.module.ComposeProbeEntry"
        private val key by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    }
}
