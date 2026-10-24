// SPDX-License-Identifier: GPL-3.0-or-later
package androidx.test.runner;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.test.internal.runner.TestRequestBuilder;
import chromahub.rhythm.runtime.api.RuntimeBootstrap;
import chromahub.rhythm.runtime.api.RuntimeComponentRegistry;
import dalvik.system.DexClassLoader;

/**
 * Run the original tests against child-loaded feature classes, without linking the implementation
 * into the base or test APK. JUnit, Espresso and Compose retain their single shared class identity.
 * This package is needed to override AndroidJUnitRunner's package-visible request-builder hook.
 */
public final class NativeModuleJUnitRunner extends AndroidJUnitRunner {
    private volatile Context featureTarget;

    @Override public Context getTargetContext() {
        Context original = super.getTargetContext();
        RuntimeBootstrap bootstrap = RuntimeComponentRegistry.optionalHost();
        if (bootstrap == null) return original;
        if (featureTarget == null) featureTarget = bootstrap.wrap(original, "instrumentation");
        return featureTarget;
    }

    @Override public Activity newActivity(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        // Compose's generic test owner must expose the same feature assets/configuration as an
        // actual wrapped music Activity. Keep the real ActivityScenario/Android lifecycle intact.
        if (name.equals("androidx.activity.ComponentActivity")) return new NativeFeatureTestActivity();
        return super.newActivity(loader, name, intent);
    }

    @Override TestRequestBuilder createTestRequestBuilder(Instrumentation instrumentation, Bundle arguments) {
        Context tests = getContext();
        RuntimeBootstrap bootstrap = RuntimeComponentRegistry.host();
        ClassLoader feature = bootstrap.wrap(getTargetContext(), "instrumentation").getClassLoader();
        ClassLoader original = getClass().getClassLoader();
        ClassLoader bridge = new DexClassLoader(tests.getApplicationInfo().sourceDir,
                getTargetContext().getCodeCacheDir().getPath(), null, original) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("chromahub.rhythm.app.")) {
                    try { return super.loadClass(name, resolve); }
                    catch (ClassNotFoundException featurePrivateType) { return feature.loadClass(name); }
                }
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    try { type = findClass(name); }
                    catch (ClassNotFoundException missingTest) { type = feature.loadClass(name); }
                }
                if (resolve) resolveClass(type);
                return type;
            }
        };
        return super.createTestRequestBuilder(instrumentation, arguments).setClassLoader(bridge);
    }
}
