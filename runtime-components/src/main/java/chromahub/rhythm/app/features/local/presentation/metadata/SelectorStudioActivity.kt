// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import chromahub.rhythm.runtime.components.RuntimeComponentActivity

class SelectorStudioActivity : RuntimeComponentActivity() {
    override val runtimeRole = "selector"
    companion object {
        const val EXTRA_KIND = "catalog.kind"
        const val EXTRA_URL = "catalog.url"
        const val EXTRA_RESULT_FILE = "catalog.result_file"
    }
}
