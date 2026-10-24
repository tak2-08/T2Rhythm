// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.base

import android.content.Context
import chromahub.rhythm.app.RhythmApplication
import chromahub.rhythm.runtime.api.RuntimeComponentRegistry

/** Android creates this once. Ordinary music code is initialized by the selected native module. */
class BaseRhythmApplication : RhythmApplication() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        RuntimeComponentRegistry.install(base, NativeRuntimeBootstrap(this, base))
    }
}
