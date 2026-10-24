// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.module

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.ContentProvider
import android.content.Context
import androidx.activity.ComponentActivity
import android.service.quicksettings.TileService
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import chromahub.rhythm.app.RhythmApplicationDelegate
import chromahub.rhythm.app.activities.*
import chromahub.rhythm.app.features.local.presentation.metadata.SelectorStudioActivityDelegate
import chromahub.rhythm.app.features.streaming.StreamingHostServiceDelegate
import chromahub.rhythm.app.features.updates.UpdateInstalledReceiverDelegate
import chromahub.rhythm.app.infrastructure.provider.RhythmAlbumArtProviderDelegate
import chromahub.rhythm.app.infrastructure.service.MediaPlaybackDelegate
import chromahub.rhythm.app.infrastructure.service.MediaPlaybackService
import chromahub.rhythm.app.infrastructure.service.RhythmTileServiceDelegate
import chromahub.rhythm.app.infrastructure.widget.glance.*
import chromahub.rhythm.runtime.api.*

/** The only entry loaded reflectively. Application models never cross the base's ABI. */
class RhythmFeatureModule : RuntimeFeatureModule {
    override fun describe() = android.os.Bundle().apply {
        putLong("compiledVersionCode", chromahub.rhythm.app.BuildConfig.VERSION_CODE.toLong())
        putString("compiledVersionName", chromahub.rhythm.app.BuildConfig.VERSION_NAME)
    }
    override fun createApplication(host: Application) = RhythmApplicationDelegate(host)

    override fun createActivity(role: String, host: Activity): ActivityCallbacks {
        val activity = host as ComponentActivity
        return when (role) {
            "main" -> MainActivityDelegate(activity)
            "external" -> ExternalPlaybackActivityDelegate(activity)
            "crash" -> CrashActivityDelegate(activity)
            "guard" -> RhythmGuardTimeoutActivityDelegate(activity)
            "selector" -> SelectorStudioActivityDelegate(activity)
            else -> error("Unknown activity role: $role")
        }
    }

    override fun createService(role: String, host: Service): ServiceCallbacks = when (role) {
        "playback" -> MediaPlaybackDelegate(host as MediaPlaybackService)
        "streaming" -> StreamingHostServiceDelegate(host)
        "tile" -> RhythmTileServiceDelegate(host as TileService)
        else -> error("Unknown service role: $role")
    }

    override fun createReceiver(role: String): ReceiverCallbacks = when (role) {
        "update-installed" -> UpdateInstalledReceiverDelegate()
        else -> error("Unknown receiver role: $role")
    }

    override fun createArtworkProvider(owner: ContentProvider) = RhythmAlbumArtProviderDelegate()

    override fun createWidget(role: String): Any = when (role) {
        "music" -> RhythmMusicWidget()
        "lyrics" -> RhythmLyricsWidget()
        "cookie" -> RhythmCookieWidget()
        "stats" -> RhythmStatsWidget()
        else -> error("Unknown widget role: $role")
    }

    override fun createWidgetAction(className: String): Any = when (className.substringAfterLast('.')) {
        "PlayPauseAction" -> PlayPauseActionDelegate()
        "SkipNextAction" -> SkipNextActionDelegate()
        "SkipPreviousAction" -> SkipPreviousActionDelegate()
        "ToggleFavoriteAction" -> ToggleFavoriteActionDelegate()
        "ToggleShuffleAction" -> ToggleShuffleActionDelegate()
        "ToggleRepeatAction" -> ToggleRepeatActionDelegate()
        else -> error("Unknown widget action: $className")
    }

    override fun createWorker(context: Context, className: String, params: Any): Any {
        require(className.startsWith("chromahub.rhythm.app."))
        val type = javaClass.classLoader!!.loadClass(className).asSubclass(ListenableWorker::class.java)
        return type.getDeclaredConstructor(Context::class.java, WorkerParameters::class.java)
            .newInstance(context, params as WorkerParameters)
    }

    override fun performServiceAction(role: String, action: String, context: Context) {
        require(role == "streaming")
        when (action) {
            "start" -> StreamingHostServiceDelegate.start(context)
            "stop" -> StreamingHostServiceDelegate.stop(context)
            else -> error("Unknown service action: $action")
        }
    }
}
