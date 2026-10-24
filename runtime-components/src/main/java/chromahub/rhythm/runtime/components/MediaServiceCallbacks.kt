// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.components

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import chromahub.rhythm.runtime.api.ServiceCallbacks

@androidx.annotation.OptIn(UnstableApi::class)
interface MediaServiceCallbacks : ServiceCallbacks {
    fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession?
    fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean, parent: Runnable)
}
