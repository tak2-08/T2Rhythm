// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.api;

import android.content.Context;

/** One host bridge per process; it is never supplied by a downloaded manifest. */
public final class RuntimeComponentRegistry {
    private static volatile RuntimeBootstrap bootstrap;
    private static volatile Context hostContext;
    private RuntimeComponentRegistry() {}

    public static synchronized void install(Context context, RuntimeBootstrap value) {
        if (bootstrap != null) throw new IllegalStateException("Runtime host already installed");
        hostContext = context;
        bootstrap = value;
    }
    public static RuntimeBootstrap host() {
        RuntimeBootstrap value = bootstrap;
        if (value == null) throw new IllegalStateException("Runtime host not installed");
        return value;
    }
    public static RuntimeBootstrap optionalHost() { return bootstrap; }
    public static String packageName() {
        Context context = hostContext;
        if (context == null) throw new IllegalStateException("Runtime host not installed");
        return context.getPackageName();
    }
}
