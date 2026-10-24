// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.runtime

import android.app.Notification
import android.app.Service
import android.content.ContextWrapper
import androidx.annotation.RequiresApi
import chromahub.rhythm.runtime.api.ServiceCallbacks

abstract class FeatureServiceDelegate(protected val host: Service) : ContextWrapper(host), ServiceCallbacks {
    val application get() = host.application
    fun stopSelf() = host.stopSelf()
    fun stopSelf(startId: Int) = host.stopSelf(startId)
    fun stopSelfResult(startId: Int) = host.stopSelfResult(startId)
    fun startForeground(id: Int, notification: Notification) = host.startForeground(id, notification)
    @RequiresApi(29)
    fun startForeground(id: Int, notification: Notification, type: Int) = host.startForeground(id, notification, type)
    fun stopForeground(flags: Int) = host.stopForeground(flags)
}
