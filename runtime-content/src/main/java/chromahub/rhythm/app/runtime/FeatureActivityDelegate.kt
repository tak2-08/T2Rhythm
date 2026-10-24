// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.runtime

import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import chromahub.rhythm.runtime.api.ActivityCallbacks

/** UI logic uses the real Activity's lifecycle, result registry and ViewModel store. */
abstract class FeatureActivityDelegate(protected val host: ComponentActivity) :
    ContextWrapper(host), ActivityCallbacks, LifecycleOwner by host,
    ViewModelStoreOwner by host, SavedStateRegistryOwner by host {
    override val lifecycle get() = host.lifecycle
    val intent: Intent get() = host.intent
    val application get() = host.application
    val window get() = host.window
    val onBackPressedDispatcher get() = host.onBackPressedDispatcher
    val isFinishing get() = host.isFinishing
    val isDestroyed get() = host.isDestroyed
    fun finish() = host.finish()
    fun recreate() = host.recreate()
    fun setIntent(intent: Intent) = host.setIntent(intent)
    fun setResult(resultCode: Int, intent: Intent? = null) = host.setResult(resultCode, intent)
    fun setContentView(view: View) = host.setContentView(view)
    fun <I, O> registerForActivityResult(contract: ActivityResultContract<I, O>, callback: ActivityResultCallback<O>) =
        host.registerForActivityResult(contract, callback)
    fun requestPermissions(permissions: Array<String>, requestCode: Int) = host.requestPermissions(permissions, requestCode)
    fun shouldShowRequestPermissionRationale(permission: String) = host.shouldShowRequestPermissionRationale(permission)
    fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle? = null) =
        host.startActivityForResult(intent, requestCode, options)
}
