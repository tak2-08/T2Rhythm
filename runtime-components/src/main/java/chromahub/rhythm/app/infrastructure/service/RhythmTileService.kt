// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.infrastructure.service

import android.content.Context
import android.service.quicksettings.TileService
import chromahub.rhythm.runtime.api.RuntimeComponentRegistry

class RhythmTileService : TileService() {
    private val callbacks by lazy { RuntimeComponentRegistry.host().feature("tile").createService("tile", this) }
    override fun attachBaseContext(base: Context) { super.attachBaseContext(RuntimeComponentRegistry.host().wrap(base, "tile")) }
    override fun onStartListening() = callbacks.onStartListening { super.onStartListening() }
    override fun onStopListening() = callbacks.onStopListening { super.onStopListening() }
    override fun onClick() = callbacks.onClick { super.onClick() }
    override fun onDestroy() = callbacks.onDestroy { super.onDestroy() }
}
