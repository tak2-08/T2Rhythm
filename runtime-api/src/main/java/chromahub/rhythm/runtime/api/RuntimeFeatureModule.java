// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.api;

import android.app.Activity;
import android.app.Application;
import android.app.Service;
import android.content.ContentProvider;
import android.content.Context;
import android.os.Bundle;

/**
 * SDK-only component boundary. Opaque values are instances of the host's pinned Glance/Work/Coil
 * libraries, checked by the concrete host bridge; application models never cross this API.
 */
public interface RuntimeFeatureModule extends NativeModuleEntry {
    Bundle describe();
    ApplicationCallbacks createApplication(Application host);
    ActivityCallbacks createActivity(String role, Activity host);
    ServiceCallbacks createService(String role, Service host);
    ReceiverCallbacks createReceiver(String role);
    ContentProvider createArtworkProvider(ContentProvider owner);
    Object createWidget(String role);
    Object createWorker(Context context, String workerClassName, Object parameters);
    Object createWidgetAction(String className);
    void performServiceAction(String role, String action, Context context);
}
