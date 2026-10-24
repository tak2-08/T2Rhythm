// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.host

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources

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
            val info = requireNotNull(base.packageManager.getPackageArchiveInfo(loaded.readOnlyDex.path, 0)?.applicationInfo) {
                "Payload has no Android resources"
            }
            info.sourceDir = loaded.readOnlyDex.path
            info.publicSourceDir = loaded.readOnlyDex.path
            info.uid = android.os.Process.myUid()
            val archiveResources = base.packageManager.getResourcesForApplication(info)
            // Use the app/activity language, density and font configuration, not just system defaults.
            val resources = Resources(archiveResources.assets, base.resources.displayMetrics,
                android.content.res.Configuration(base.resources.configuration))
            return ModuleResourceContext(base, resources, loaded.entry.javaClass.classLoader!!)
        }
    }
}
