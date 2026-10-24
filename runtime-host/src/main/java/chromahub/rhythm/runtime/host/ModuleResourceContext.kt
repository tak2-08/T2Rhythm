// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.host

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Public-API resource attachment, including API26. The signed payload remains uninstalled. The base
 * and feature must preserve their fixed resource IDs; private copies isolate AssetManager changes
 * across component and override configurations without hidden AssetManager APIs.
 */
class ModuleResourceContext private constructor(
    base: Context,
    private val moduleResources: Resources,
    private val moduleClassLoader: ClassLoader,
    private val loaded: LoadedNativeModule<*>,
) : ContextWrapper(base) {
    override fun getResources(): Resources = moduleResources
    override fun getAssets(): AssetManager = moduleResources.assets
    override fun getClassLoader(): ClassLoader = moduleClassLoader

    override fun createConfigurationContext(overrideConfiguration: android.content.res.Configuration): Context =
        attach(baseContext.createConfigurationContext(overrideConfiguration), loaded)

    /** Configuration snapshots must track the real component (locale, night mode, font and density). */
    @Suppress("DEPRECATION")
    fun refreshConfiguration() {
        moduleResources.updateConfiguration(baseContext.resources.configuration, baseContext.resources.displayMetrics)
    }

    companion object {
        private val contexts = java.util.WeakHashMap<ModuleResourceContext, Boolean>()
        private val owners = java.util.WeakHashMap<Context, Long>()
        private var nextOwner = 0L
        private val process = "${android.os.Process.myPid()}-${java.util.UUID.randomUUID()}"

        @Synchronized fun refreshAll() {
            // All wrappers keep the override configuration of their own Android Context.
            contexts.keys.toList().forEach { it.refreshConfiguration() }
        }
        @Synchronized
        @Suppress("DEPRECATION") // Public API26-compatible Resources constructor; no hidden AssetManager APIs.
        fun attach(base: Context, loaded: LoadedNativeModule<*>): ModuleResourceContext {
            val configuration = android.content.res.Configuration(base.resources.configuration)
            val configurationId = MessageDigest.getInstance("SHA-256")
                .digest((configuration.toString() + "|" + base.resources.displayMetrics.toString()).toByteArray())
                .joinToString("") { "%02x".format(it) }
            // Resources(assets, ..., config) changes AssetManager configuration. Separate private
            // copies isolate framework cache keys. Android's app sandbox rejects hard links.
            val owner = owners.getOrPut(base) { ++nextOwner }
            // AssetManager is mutable. A locale/font change in one component must not alter an
            // explicitly configured context that happened to start with the same configuration.
            val directory = File(base.codeCacheDir,
                "runtime-resources/${loaded.reference.archiveSha256}/$process/$owner/$configurationId")
            check(directory.mkdirs() || directory.isDirectory)
            val alias = File(directory, "content.apk")
            if (!alias.exists()) {
                val temporary = File.createTempFile("resources-", ".apk", directory)
                try {
                    FileOutputStream(temporary).use { output ->
                        check(temporary.setReadOnly())
                        loaded.readOnlyDex.inputStream().use { input ->
                            val bytes = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(bytes)
                                if (count == -1) break
                                total += count
                                require(total <= loaded.reference.manifest.payloadSize)
                                output.write(bytes, 0, count)
                            }
                            require(total == loaded.reference.manifest.payloadSize)
                        }
                        output.fd.sync()
                    }
                    require(temporary.length() == loaded.reference.manifest.payloadSize &&
                        digest(temporary) == loaded.reference.manifest.payloadSha256)
                    check(temporary.renameTo(alias))
                } finally {
                    temporary.delete()
                }
            }
            require(!Files.isSymbolicLink(alias.toPath()))
            val target = Os.stat(alias.path)
            require(target.st_mode and (OsConstants.S_IWUSR or OsConstants.S_IWGRP or OsConstants.S_IWOTH) == 0)
            require(alias.length() == loaded.reference.manifest.payloadSize &&
                digest(alias) == loaded.reference.manifest.payloadSha256)
            val info = requireNotNull(base.packageManager.getPackageArchiveInfo(alias.path, 0)?.applicationInfo) {
                "Payload has no Android resources"
            }
            info.sourceDir = alias.path
            info.publicSourceDir = alias.path
            info.uid = android.os.Process.myUid()
            val archiveResources = base.packageManager.getResourcesForApplication(info)
            // Use the app/activity language, density and font configuration, not just system defaults.
            val resources = Resources(archiveResources.assets, base.resources.displayMetrics,
                configuration)
            return ModuleResourceContext(base, resources, loaded.entry.javaClass.classLoader!!, loaded).also { contexts[it] = true }
        }

        private fun digest(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val bytes = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(bytes)
                    if (count == -1) break
                    digest.update(bytes, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
