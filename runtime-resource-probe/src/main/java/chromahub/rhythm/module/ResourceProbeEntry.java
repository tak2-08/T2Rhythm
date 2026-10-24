// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.module;

import android.content.Context;
import android.view.View;
import android.widget.TextView;
import chromahub.rhythm.module.resources.R;
import chromahub.rhythm.runtime.api.NativeUiModule;

/** Reads compiled resources that exist only in the downloaded payload fixture. */
public class ResourceProbeEntry implements NativeUiModule {
    @Override public View createView(Context context) {
        TextView view = new TextView(context);
        view.setText(context.getString(R.string.runtime_probe_text));
        view.setCompoundDrawablesWithIntrinsicBounds(context.getDrawable(R.drawable.runtime_probe_badge), null, null, null);
        return view;
    }
}
