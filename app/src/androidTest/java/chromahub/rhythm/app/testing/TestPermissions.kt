// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.testing

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/** UiAutomation.grantRuntimePermission is API28+, while the application and tests support API26. */
internal fun grantTestPermission(permission: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    if (Build.VERSION.SDK_INT >= 28) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
    else ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission")
    ).use { it.readBytes() }
}
