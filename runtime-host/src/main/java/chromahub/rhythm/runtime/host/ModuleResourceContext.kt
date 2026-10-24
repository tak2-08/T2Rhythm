// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.host

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Public API resource-loading experiment, including API26. The payload is already verified and
 * read-only. Parsing its manifest registers no activity/service/permission or Android package.
 * Full theme/library resource-ID compatibility remains a separate migration concern.
 */
class ModuleResourceContext private constructor(
    base: Context,
    private val moduleResources: Resources,
    private val moduleClassLoader: ClassLoader,
) : ContextWrapper(base) {
    override fun getResources(): Resources = moduleResources
    override fun getAssets(): AssetManager = moduleResources.assets
    override fun getClassLoader(): ClassLoader = moduleClassLoader

    companion object {
        @Suppress("DEPRECATION") // Public API26-compatible Resources constructor; no hidden AssetManager APIs.
        fun attach(base: Context, loaded: LoadedNativeModule): ModuleResourceContext {
            val configuration = android.content.res.Configuration(base.resources.configuration)
            val configurationId = MessageDigest.getInstance("SHA-256")
                .digest((configuration.toString() + "|" + base.resources.displayMetrics.toString()).toByteArray())
                .joinToString("") { "%02x".format(it) }
            // Resources(assets, ..., config) changes AssetManager configuration. Distinct hard-link
            // paths give each configuration its own framework resource-cache key without copying
            // the APK or using hidden AssetManager APIs; the verified inode remains read-only.
            val directory = File(loaded.readOnlyDex.parentFile, "resource-contexts/$configurationId")
            check(directory.mkdirs() || directory.isDirectory)
            val alias = File(directory, "content.apk")
            try { Os.link(loaded.readOnlyDex.path, alias.path) } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EEXIST) throw error
            }
            require(!Files.isSymbolicLink(alias.toPath()))
            val source = Os.stat(loaded.readOnlyDex.path)
            val target = Os.stat(alias.path)
            require(source.st_dev == target.st_dev && source.st_ino == target.st_ino)
            require(target.st_mode and (OsConstants.S_IWUSR or OsConstants.S_IWGRP or OsConstants.S_IWOTH) == 0)
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
            return ModuleResourceContext(base, resources, loaded.entry.javaClass.classLoader!!)
        }
    }
}
