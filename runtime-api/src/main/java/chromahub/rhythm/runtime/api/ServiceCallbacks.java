// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.api;

import android.content.Intent;
import android.os.IBinder;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public interface ServiceCallbacks {
    default void onCreate(Runnable parent) { parent.run(); }
    default int onStartCommand(Intent intent, int flags, int startId, IntSupplier parent) { return parent.getAsInt(); }
    default IBinder onBind(Intent intent, Supplier<IBinder> parent) { return parent.get(); }
    default void onDestroy(Runnable parent) { parent.run(); }
    default void onTaskRemoved(Intent rootIntent, Runnable parent) { parent.run(); }
    default void onStartListening(Runnable parent) { parent.run(); }
    default void onStopListening(Runnable parent) { parent.run(); }
    default void onClick(Runnable parent) { parent.run(); }
}
