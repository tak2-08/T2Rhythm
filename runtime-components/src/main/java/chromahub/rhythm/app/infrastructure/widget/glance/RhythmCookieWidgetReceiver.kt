// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.infrastructure.widget.glance
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import chromahub.rhythm.runtime.api.RuntimeComponentRegistry
class RhythmCookieWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget
        get() = RuntimeComponentRegistry.host().feature("widget").createWidget("cookie") as GlanceAppWidget
}
