// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.infrastructure.service

import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaNotification
import chromahub.rhythm.runtime.api.RuntimeComponentRegistry
import chromahub.rhythm.runtime.components.MediaServiceCallbacks

@androidx.annotation.OptIn(UnstableApi::class)
class MediaPlaybackService : MediaLibraryService() {
    private val callbacks: MediaServiceCallbacks by lazy {
        RuntimeComponentRegistry.host().feature("playback").createService("playback", this) as MediaServiceCallbacks
    }
    override fun attachBaseContext(base: Context) { super.attachBaseContext(RuntimeComponentRegistry.host().wrap(base, "playback")) }
    override fun onCreate() = callbacks.onCreate { super.onCreate() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.setExtrasClassLoader(classLoader)
        return callbacks.onStartCommand(intent, flags, startId) { super.onStartCommand(intent, flags, startId) }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = callbacks.onGetSession(controllerInfo)
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) =
        callbacks.onUpdateNotification(session, startInForegroundRequired) { super.onUpdateNotification(session, startInForegroundRequired) }
    override fun onDestroy() = callbacks.onDestroy { super.onDestroy() }
    override fun onTaskRemoved(rootIntent: Intent?) = callbacks.onTaskRemoved(rootIntent) { super.onTaskRemoved(rootIntent) }
    fun runtimeSetNotificationProvider(provider: MediaNotification.Provider) = setMediaNotificationProvider(provider)
    companion object {
        const val ACTION_UPDATE_SETTINGS = "chromahub.rhythm.app.action.UPDATE_SETTINGS"
        const val ACTION_PLAY_EXTERNAL_FILE = "chromahub.rhythm.app.action.PLAY_EXTERNAL_FILE"
        const val ACTION_INIT_SERVICE = "chromahub.rhythm.app.action.INIT_SERVICE"
        const val ACTION_START_SLEEP_TIMER = "chromahub.rhythm.app.action.START_SLEEP_TIMER"
        const val ACTION_STOP_SLEEP_TIMER = "chromahub.rhythm.app.action.STOP_SLEEP_TIMER"
        const val ACTION_EXTEND_SLEEP_TIMER = "chromahub.rhythm.app.action.EXTEND_SLEEP_TIMER"
        const val ACTION_SET_EQUALIZER_ENABLED = "chromahub.rhythm.app.action.SET_EQUALIZER_ENABLED"
        const val ACTION_SET_EQUALIZER_BAND = "chromahub.rhythm.app.action.SET_EQUALIZER_BAND"
        const val ACTION_SET_BASS_BOOST = "chromahub.rhythm.app.action.SET_BASS_BOOST"
        const val ACTION_SET_VIRTUALIZER = "chromahub.rhythm.app.action.SET_VIRTUALIZER"
        const val ACTION_SET_MONO_AUDIO = "chromahub.rhythm.app.action.SET_MONO_AUDIO"
        const val ACTION_APPLY_EQUALIZER_PRESET = "chromahub.rhythm.app.action.APPLY_EQUALIZER_PRESET"
        const val ACTION_GET_EQUALIZER_DIAGNOSTICS = "chromahub.rhythm.app.action.GET_EQUALIZER_DIAGNOSTICS"
        const val ACTION_PLAY_PAUSE = "chromahub.rhythm.app.action.PLAY_PAUSE"
        const val ACTION_SKIP_NEXT = "chromahub.rhythm.app.action.SKIP_NEXT"
        const val ACTION_SKIP_PREVIOUS = "chromahub.rhythm.app.action.SKIP_PREVIOUS"
        const val ACTION_TOGGLE_FAVORITE = "chromahub.rhythm.app.action.TOGGLE_FAVORITE"
        const val ACTION_TOGGLE_SHUFFLE = "chromahub.rhythm.app.action.TOGGLE_SHUFFLE"
        const val ACTION_TOGGLE_REPEAT = "chromahub.rhythm.app.action.TOGGLE_REPEAT"
        const val BROADCAST_SLEEP_TIMER_STATUS = "chromahub.rhythm.app.broadcast.SLEEP_TIMER_STATUS"
        const val EXTRA_TIMER_ACTIVE = "timer_active"
        const val EXTRA_REMAINING_TIME = "remaining_time"
        const val EXTRA_TOTAL_TIME = "total_time"
        const val ACTION_SHUFFLE_STATE_CHANGED = "chromahub.rhythm.app.action.SHUFFLE_STATE_CHANGED"
        const val EXTRA_SHUFFLE_ENABLED = "shuffle_enabled"
        const val ACTION_GET_AUDIO_SESSION_ID = "chromahub.rhythm.app.action.GET_AUDIO_SESSION_ID"
        const val BROADCAST_AUDIO_SESSION_ID = "chromahub.rhythm.app.broadcast.AUDIO_SESSION_ID"
        const val EXTRA_AUDIO_SESSION_ID = "audio_session_id"
        const val ACTION_MUTE = "chromahub.rhythm.app.action.MUTE"
        const val ACTION_UNMUTE = "chromahub.rhythm.app.action.UNMUTE"
        const val ACTION_TOGGLE_MUTE = "chromahub.rhythm.app.action.TOGGLE_MUTE"
        const val ACTION_ZERO_VOLUME_PAUSE = "chromahub.rhythm.app.action.ZERO_VOLUME_PAUSE"
        const val ACTION_ZERO_VOLUME_RESUME = "chromahub.rhythm.app.action.ZERO_VOLUME_RESUME"
        const val REPEAT_MODE_ALL = "repeat_all"
        const val REPEAT_MODE_ONE = "repeat_one"
        const val REPEAT_MODE_OFF = "repeat_off"
        const val SHUFFLE_MODE_ON = "shuffle_on"
        const val SHUFFLE_MODE_OFF = "shuffle_off"
        const val FAVORITE_ON = "favorite_on"
        const val FAVORITE_OFF = "favorite_off"
    }
}
