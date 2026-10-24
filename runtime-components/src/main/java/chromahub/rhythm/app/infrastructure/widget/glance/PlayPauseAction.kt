// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.infrastructure.widget.glance
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import chromahub.rhythm.runtime.api.RuntimeComponentRegistry
class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val callback = RuntimeComponentRegistry.host().feature("widget-action").createWidgetAction("PlayPauseAction") as ActionCallback
        callback.onAction(context, glanceId, parameters)
    }
}
