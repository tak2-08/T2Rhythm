// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.api;

import android.content.Context;
import android.content.res.Resources;

/** Installed by the base Application before Android creates any component. */
public interface RuntimeBootstrap {
    RuntimeFeatureModule feature(String reason);
    Context wrap(Context context, String reason);
    ApplicationCallbacks application(boolean initialize);
    Object workerFactory();
    default Resources applicationResources(Resources fallback) { return fallback; }
    default ClassLoader applicationClassLoader(ClassLoader fallback) { return fallback; }
    default Context applicationConfigurationContext(Context fallback) { return fallback; }
    default void reportUiReady() { }
    RuntimeUpdateController updates();
    default String selectedArchiveHash() { return ""; }
    default void refreshConfiguration() { }
}
