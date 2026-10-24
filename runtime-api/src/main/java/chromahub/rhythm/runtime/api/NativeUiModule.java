// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.api;

import android.content.Context;
import android.view.View;

/** Experimental shared boundary. The returned view may contain the module's native Compose UI. */
public interface NativeUiModule {
    View createView(Context context);
}
