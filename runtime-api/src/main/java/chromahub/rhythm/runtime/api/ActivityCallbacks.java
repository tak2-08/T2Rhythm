// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.api;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;

/** Calls retain the original component's placement of super, rather than imposing a new order. */
public interface ActivityCallbacks {
    default void onCreate(Bundle state, Runnable parent) { parent.run(); }
    default void onStart(Runnable parent) { parent.run(); }
    default void onResume(Runnable parent) { parent.run(); }
    default void onPause(Runnable parent) { parent.run(); }
    default void onStop(Runnable parent) { parent.run(); }
    default void onDestroy(Runnable parent) { parent.run(); }
    default void onNewIntent(Intent intent, Runnable parent) { parent.run(); }
    default void onSaveInstanceState(Bundle state, Runnable parent) { parent.run(); }
    default void onWindowFocusChanged(boolean focused, Runnable parent) { parent.run(); }
    default void onConfigurationChanged(Configuration configuration, Runnable parent) { parent.run(); }
}
