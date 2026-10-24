// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.api;

import android.content.res.Configuration;

public interface ApplicationCallbacks {
    void onCreate(Runnable parent);
    Object newImageLoader();
    default void onTerminate(Runnable parent) { parent.run(); }
    default void onTrimMemory(int level, Runnable parent) { parent.run(); }
    default void onConfigurationChanged(Configuration configuration, Runnable parent) { parent.run(); }
}
