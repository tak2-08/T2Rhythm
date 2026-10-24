// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.shared.data.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Feature-owned state: Android's fixed service component must not own application model classes. */
object PlaybackQualityState {
    internal val mutable = MutableStateFlow<PlaybackQualityInfo?>(null)
    val current = mutable.asStateFlow()
}
