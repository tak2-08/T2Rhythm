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
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Experimental trial loader, currently used ONLY by instrumentation tests.
 * The real base must own one loader per process and durably save Activate.state before calling.
 * Stable-version loading, storage journal/recovery and the application's UI/service API are not
 * implemented here. This class alone is not an application updater.
 */
class NativeModuleTrialLoader(
    context: Context,
    private val verifier: RuntimePackageVerifier,
    private val host: HostEnvironment,
    private val sharedClassLoader: ClassLoader,
) {
    private val context = context.applicationContext
    private var attemptedArchive: String? = null
    private var loaded: LoadedNativeModule? = null

    @Synchronized
    fun loadTrial(decision: StartupDecision.Activate, archive: File): LoadedNativeModule {
        val reference = decision.state.active
        check(decision.state.trialInProgress && decision.state.previousGood != null)
        attemptedArchive?.let { check(it == reference.archiveSha256) { "A process cannot switch module versions" } }
        loaded?.let { return it }
        val privateRoot = context.noBackupFilesDir.canonicalFile
        require(archive.canonicalPath.startsWith(privateRoot.path + File.separator))
        require(!Files.isSymbolicLink(archive.toPath()) && archive.isFile)
        requireReadOnly(archive)
        val verified = verifier.verify(archive, host)
        require(verified.reference == reference) { "Activation belongs to a different verified archive" }
        // An unsuccessful class initialization also pins this attempt: never mix versions in-process.
        attemptedArchive = reference.archiveSha256
        val dex = materializeDex(archive, reference)
        val loader = DexClassLoader(dex.path, context.codeCacheDir.path, null, sharedClassLoader)
        val entryClass = loader.loadClass(reference.manifest.entryPointClass)
        require(entryClass.classLoader === loader) { "Entry point was bundled in the host" }
        require(NativeUiModule::class.java.isAssignableFrom(entryClass)) { "Entry point does not implement the shared contract" }
        val instance = entryClass.getDeclaredConstructor().newInstance() as NativeUiModule
        return LoadedNativeModule(reference, instance, dex).also { loaded = it }
    }

    private fun materializeDex(archive: File, reference: ModuleReference): File {
        val directory = File(context.noBackupFilesDir, "runtime-trial/${reference.archiveSha256}")
        check(directory.mkdirs() || directory.isDirectory)
        val dex = File(directory, "content.dex.jar")
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

data class LoadedNativeModule internal constructor(
    val reference: ModuleReference,
    val entry: NativeUiModule,
    val readOnlyDex: File,
)
