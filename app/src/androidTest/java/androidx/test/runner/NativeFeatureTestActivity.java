// SPDX-License-Identifier: GPL-3.0-or-later
package androidx.test.runner;

import android.content.Context;
import androidx.activity.ComponentActivity;
import chromahub.rhythm.runtime.api.RuntimeComponentRegistry;

/** Test-only counterpart of the installed Activities' public resource-context attachment. */
public final class NativeFeatureTestActivity extends ComponentActivity {
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(RuntimeComponentRegistry.host().wrap(base, "instrumentation"));
    }
}
