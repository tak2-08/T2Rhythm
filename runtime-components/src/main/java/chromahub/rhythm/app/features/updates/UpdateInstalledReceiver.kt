// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import chromahub.rhythm.runtime.api.RuntimeComponentRegistry

class UpdateInstalledReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val host = RuntimeComponentRegistry.host()
        intent.setExtrasClassLoader(host.wrap(context, "update-receiver").classLoader)
        host.feature("update-receiver").createReceiver("update-installed").onReceive(this, context, intent)
    }
}
