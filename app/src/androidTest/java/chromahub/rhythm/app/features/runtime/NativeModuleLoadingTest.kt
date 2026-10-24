// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.runtime

import android.graphics.Bitmap
import android.system.Os
import android.system.OsConstants
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
import chromahub.rhythm.runtime.api.NativeUiModule
import chromahub.rhythm.runtime.host.NativeModuleTrialLoader
import com.google.gson.Gson
import dalvik.system.DexClassLoader
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.UUID
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

    private fun makeArchive(version: Long, tamperPayload: Boolean = false, writable: Boolean = false): File {
        val payload = instrumentation.context.assets.open("content.dex.jar").use { it.readBytes() }
        val notes = "Native runtime probe $version".toByteArray()
        val manifest = Gson().toJson(linkedMapOf(
            "formatVersion" to 1,
            "moduleId" to host.moduleId,
            "versionCode" to version,
            "versionName" to "probe-$version-${UUID.randomUUID()}",
            "signingKeyId" to RuntimePackageVerifier.keyId(key.public),
            "entryPointClass" to ENTRY,
            "hostApi" to host.hostApi,
            "runtimeFingerprint" to host.runtimeFingerprint,
            "minBaseVersion" to host.baseVersion,
            "maxBaseVersion" to host.baseVersion,
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
            initSign(key.private); update(manifest); sign()
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
        val archive = makeArchive(2)
        val decision = activation(archive)
        val loaded = loader().loadTrial(decision, archive)
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

    companion object {
        private const val MARKER = "t2rhythm.runtime.probe.initialized"
        private const val ENTRY = "chromahub.rhythm.module.ComposeProbeEntry"
        private val key by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    }
}
