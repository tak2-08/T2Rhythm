// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app

import android.app.Application
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import androidx.work.Configuration as WorkConfiguration
import androidx.work.WorkerFactory
import chromahub.rhythm.runtime.api.RuntimeComponentRegistry
import coil.ImageLoader
import coil.ImageLoaderFactory

/** The base subclass installs its bootstrap in attachBaseContext, before system providers start. */
open class RhythmApplication : Application(), ImageLoaderFactory, WorkConfiguration.Provider {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        instance = this
    }

    override fun newImageLoader(): ImageLoader =
        RuntimeComponentRegistry.host().application(true)!!.newImageLoader() as ImageLoader

    override val workManagerConfiguration: WorkConfiguration
        get() = WorkConfiguration.Builder()
            .setDefaultProcessName(packageName)
            .setWorkerFactory(RuntimeComponentRegistry.host().workerFactory() as WorkerFactory)
            .build()

    override fun getResources(): Resources =
        RuntimeComponentRegistry.optionalHost()?.applicationResources(super.getResources()) ?: super.getResources()
    override fun getAssets(): AssetManager = resources.assets
    override fun getClassLoader(): ClassLoader =
        RuntimeComponentRegistry.optionalHost()?.applicationClassLoader(super.getClassLoader()) ?: super.getClassLoader()
    override fun createConfigurationContext(overrideConfiguration: Configuration): Context {
        val configured = super.createConfigurationContext(overrideConfiguration)
        return RuntimeComponentRegistry.optionalHost()?.applicationConfigurationContext(configured) ?: configured
    }

    override fun onTerminate() {
        val callbacks = RuntimeComponentRegistry.optionalHost()?.application(false)
        if (callbacks == null) super.onTerminate() else callbacks.onTerminate { super.onTerminate() }
    }
    override fun onTrimMemory(level: Int) {
        val callbacks = RuntimeComponentRegistry.optionalHost()?.application(false)
        if (callbacks == null) super.onTrimMemory(level) else callbacks.onTrimMemory(level) { super.onTrimMemory(level) }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        RuntimeComponentRegistry.optionalHost()?.refreshConfiguration()
        val callbacks = RuntimeComponentRegistry.optionalHost()?.application(false)
        if (callbacks == null) super.onConfigurationChanged(newConfig)
        else callbacks.onConfigurationChanged(newConfig) { super.onConfigurationChanged(newConfig) }
    }
    companion object {
        lateinit var instance: RhythmApplication
            private set
    }
}
