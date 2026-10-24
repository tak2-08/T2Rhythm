// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.api;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public interface ReceiverCallbacks {
    void onReceive(BroadcastReceiver owner, Context context, Intent intent);
}
