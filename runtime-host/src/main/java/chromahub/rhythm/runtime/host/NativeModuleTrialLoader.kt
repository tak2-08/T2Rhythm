// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.host

import android.content.Context
import android.system.Os
import android.system.OsConstants
import chromahub.rhythm.runtime.HostEnvironment
import chromahub.rhythm.runtime.ModuleReference
import chromahub.rhythm.runtime.RuntimePackageVerifier
import chromahub.rhythm.runtime.StartupDecision
import chromahub.rhythm.runtime.api.NativeUiModule
import chromahub.rhythm.runtime.api.NativeModuleEntry
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Native DEX loading for trials, known-good starts and recovered versions. The installed base owns
 * one instance per process and commits the startup journal before consuming a decision. Package
 * verification, immutable files and a shared SDK contract are required for every startup path.
 */
class NativeModuleTrialLoader(
    context: Context,
    private val verifier: RuntimePackageVerifier,
    private val host: HostEnvironment,
    private val sharedClassLoader: ClassLoader,
) {
    private val context = context.applicationContext
    private var attemptedArchive: String? = null
    private var loaded: LoadedNativeModule<*>? = null

    fun loadTrial(decision: StartupDecision.Activate, archive: File): LoadedNativeModule<NativeUiModule> =
        loadTrial(decision, archive, NativeUiModule::class.java)

    @Synchronized
    fun <T : NativeModuleEntry> loadTrial(decision: StartupDecision.Activate, archive: File, contract: Class<T>): LoadedNativeModule<T> {
        check(decision.state.trialInProgress && decision.state.previousGood != null)
        return loadStartup(decision, archive, contract)
    }

    /** Consumes a committed startup decision; known-good and recovered modules also need loading. */
    @Synchronized
    fun <T : NativeModuleEntry> loadStartup(decision: StartupDecision, archive: File, contract: Class<T>): LoadedNativeModule<T> {
        require(decision !is StartupDecision.RecoveryRequired)
        val reference = decision.state.active
        attemptedArchive?.let { check(it == reference.archiveSha256) { "A process cannot switch module versions" } }
        loaded?.let {
            require(contract.isInstance(it.entry))
            @Suppress("UNCHECKED_CAST")
            return it as LoadedNativeModule<T>
        }
        val privateRoot = context.noBackupFilesDir.canonicalFile
        require(archive.canonicalPath.startsWith(privateRoot.path + File.separator))
        require(!Files.isSymbolicLink(archive.toPath()) && archive.isFile)
        requireReadOnly(archive)
        val verified = verifier.verify(archive, host)
        require(verified.reference == reference) { "Activation belongs to a different verified archive" }
        // An unsuccessful class initialization also pins this attempt: never mix versions in-process.
        attemptedArchive = reference.archiveSha256
        val dex = materializeDex(archive, reference)
        val info = context.applicationInfo
        val nativePaths = (listOfNotNull(info.nativeLibraryDir) +
            (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).flatMap { apk ->
                android.os.Build.SUPPORTED_ABIS.map { abi -> "$apk!/lib/$abi" }
            }).joinToString(File.pathSeparator)
        val loader = FeatureDexClassLoader(dex.path, context.codeCacheDir.path, nativePaths, sharedClassLoader)
        val entryClass = loader.loadClass(reference.manifest.entryPointClass)
        require(entryClass.classLoader === loader) { "Entry point was bundled in the host" }
        require(contract.isAssignableFrom(entryClass)) { "Entry point does not implement the shared contract" }
        val instance = requireNotNull(contract.cast(entryClass.getDeclaredConstructor().newInstance()))
        return LoadedNativeModule(reference, instance, dex).also { loaded = it }
    }

    private fun materializeDex(archive: File, reference: ModuleReference): File {
        val directory = File(context.noBackupFilesDir, "runtime-trial/${reference.archiveSha256}")
        check(directory.mkdirs() || directory.isDirectory)
        // APK-shaped payloads may also carry compiled Android resources; no installation occurs.
        val dex = File(directory, "content.apk")
        if (dex.exists()) {
            require(!Files.isSymbolicLink(dex.toPath()))
            requireReadOnly(dex)
            require(dex.length() == reference.manifest.payloadSize && digest(dex) == reference.manifest.payloadSha256)
            return dex
        }
        val temporary = File.createTempFile("candidate-", ".dex.jar", directory)
        try {
            FileOutputStream(temporary).use { output ->
                // Android 14+: open first, mark read-only BEFORE writing through the descriptor.
                check(temporary.setReadOnly())
                requireReadOnly(temporary)
                ZipFile(archive).use { zip ->
                    zip.getInputStream(zip.getEntry("content.dex.jar")).use { input ->
                        val buffer = ByteArray(16 * 1024)
                        var size = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            size += count
                            require(size <= reference.manifest.payloadSize)
                            output.write(buffer, 0, count)
                        }
                        require(size == reference.manifest.payloadSize)
                    }
                }
                output.fd.sync()
            }
            require(digest(temporary) == reference.manifest.payloadSha256)
            check(temporary.renameTo(dex))
            requireReadOnly(dex)
            return dex
        } finally {
            temporary.delete()
        }
    }

    private fun requireReadOnly(file: File) {
        val mode = Os.stat(file.path).st_mode
        require(mode and (OsConstants.S_IWUSR or OsConstants.S_IWGRP or OsConstants.S_IWOTH) == 0) {
            "Runtime files must be read-only"
        }
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Resource/version symbols belong to the feature; shared contracts and libraries stay parent-first. */
private class FeatureDexClassLoader(path: String, optimizedDirectory: String, nativePaths: String, parent: ClassLoader) :
    DexClassLoader(path, optimizedDirectory, nativePaths, parent) {
    @Synchronized override fun loadClass(name: String, resolve: Boolean): Class<*> {
        val systemComponent = SYSTEM_COMPONENTS.any { name == it || name.startsWith("$it$") }
        val featureSymbol = name.startsWith("chromahub.rhythm.app.") && !systemComponent
        if (!featureSymbol) return super.loadClass(name, resolve)
        val loaded = findLoadedClass(name) ?: try { findClass(name) } catch (_: ClassNotFoundException) {
            super.loadClass(name, false)
        }
        if (resolve) resolveClass(loaded)
        return loaded
    }

    companion object {
        private val SYSTEM_COMPONENTS = setOf(
            "chromahub.rhythm.app.RhythmApplication",
            "chromahub.rhythm.app.activities.MainActivity",
            "chromahub.rhythm.app.activities.ExternalPlaybackActivity",
            "chromahub.rhythm.app.activities.CrashActivity",
            "chromahub.rhythm.app.activities.RhythmGuardTimeoutActivity",
            "chromahub.rhythm.app.features.local.presentation.metadata.SelectorStudioActivity",
            "chromahub.rhythm.app.features.streaming.StreamingHostService",
            "chromahub.rhythm.app.features.updates.UpdateInstalledReceiver",
            "chromahub.rhythm.app.infrastructure.service.MediaPlaybackService",
            "chromahub.rhythm.app.infrastructure.service.RhythmTileService",
            "chromahub.rhythm.app.infrastructure.provider.RhythmAlbumArtProvider",
            "chromahub.rhythm.app.infrastructure.widget.glance.RhythmWidgetReceiver",
            "chromahub.rhythm.app.infrastructure.widget.glance.RhythmLyricsWidgetReceiver",
            "chromahub.rhythm.app.infrastructure.widget.glance.RhythmCookieWidgetReceiver",
            "chromahub.rhythm.app.infrastructure.widget.glance.RhythmStatsWidgetReceiver",
        )
    }
}

data class LoadedNativeModule<T : NativeModuleEntry> internal constructor(
    val reference: ModuleReference,
    val entry: T,
    val readOnlyDex: File,
)
