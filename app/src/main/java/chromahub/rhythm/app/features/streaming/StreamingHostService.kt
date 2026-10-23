// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import chromahub.rhythm.app.R
import chromahub.rhythm.app.activities.MainActivity
import kotlinx.coroutines.*

/** Explicitly started by the user; a visible stop action owns the lifetime and wake lock. */
class StreamingHostService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manager by lazy { PrivateStreaming.get(this) }
    private var startup: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var ready = false
    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (!plugged && level >= 0 && level * 100 / scale < 15) {
                ready = false
                manager.fail(getString(R.string.streaming_low_battery)); stopSelf()
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "T2Rhythm Streaming", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, StreamingHostService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("T2Rhythm Streaming").setContentText(getString(R.string.streaming_notification))
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, getString(R.string.streaming_stop), stop).build()
        try {
            ServiceCompat.startForeground(this, 1323, notification, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "T2Rhythm:private-streaming").apply {
                setReferenceCounted(false)
                @Suppress("WakelockTimeout") acquire()
            }
            ready = true
            ContextCompat.registerReceiver(this, battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (_: Exception) { manager.fail(getString(R.string.streaming_start_error)); stopSelf() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ready) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == STOP) { startup?.cancel(); manager.stop(); stopSelf(); return START_NOT_STICKY }
        if (startup?.isActive != true && !manager.state.value.running) startup = scope.launch {
            manager.start(scope)
            if (!manager.state.value.running) stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        startup?.cancel(); scope.cancel(); manager.stop()
        runCatching { unregisterReceiver(battery) }
        wake?.let { if (it.isHeld) it.release() }; wake = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "private_music_server"
        private const val STOP = "chromahub.rhythm.app.STOP_PRIVATE_STREAMING"
        fun start(context: Context) {
            try { ContextCompat.startForegroundService(context, Intent(context, StreamingHostService::class.java)) }
            catch (_: Exception) { PrivateStreaming.get(context).fail(context.getString(R.string.streaming_start_error)) }
        }
        fun stop(context: Context) { context.stopService(Intent(context, StreamingHostService::class.java)) }
    }
}
