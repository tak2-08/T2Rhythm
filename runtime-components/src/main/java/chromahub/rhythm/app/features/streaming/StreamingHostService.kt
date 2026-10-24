// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import chromahub.rhythm.runtime.api.RuntimeComponentRegistry

class StreamingHostService : Service() {
    private val callbacks by lazy { RuntimeComponentRegistry.host().feature("streaming").createService("streaming", this) }
    override fun attachBaseContext(base: Context) { super.attachBaseContext(RuntimeComponentRegistry.host().wrap(base, "streaming")) }
    override fun onCreate() = callbacks.onCreate { super.onCreate() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.setExtrasClassLoader(classLoader)
        return callbacks.onStartCommand(intent, flags, startId) { super.onStartCommand(intent, flags, startId) }
    }
    override fun onBind(intent: Intent?): IBinder? = callbacks.onBind(intent) { null }
    override fun onDestroy() = callbacks.onDestroy { super.onDestroy() }
    override fun onTaskRemoved(rootIntent: Intent?) = callbacks.onTaskRemoved(rootIntent) { super.onTaskRemoved(rootIntent) }
    companion object {
        fun start(context: Context) = RuntimeComponentRegistry.host().feature("streaming").performServiceAction("streaming", "start", context)
        fun stop(context: Context) = RuntimeComponentRegistry.host().feature("streaming").performServiceAction("streaming", "stop", context)
    }
}
