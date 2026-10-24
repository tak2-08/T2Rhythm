// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.components

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import chromahub.rhythm.runtime.api.ActivityCallbacks
import chromahub.rhythm.runtime.api.RuntimeComponentRegistry

/** Only the registered Activity owns Android lifecycle state, ViewModelStore and result registry. */
abstract class RuntimeComponentActivity : ComponentActivity() {
    protected abstract val runtimeRole: String
    private val callbacks: ActivityCallbacks by lazy { RuntimeComponentRegistry.host().feature(runtimeRole).createActivity(runtimeRole, this) }
    override fun attachBaseContext(base: Context) { super.attachBaseContext(RuntimeComponentRegistry.host().wrap(base, runtimeRole)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        val bootstrap = RuntimeComponentRegistry.host()
        val compatibleState = savedInstanceState?.let { outer ->
            runCatching {
                outer.classLoader = javaClass.classLoader
                if (outer.getString(RUNTIME_STATE_KEY) == bootstrap.selectedArchiveHash()) outer.getBundle(RUNTIME_UI_STATE_KEY) else null
            }.getOrNull()
        }
        compatibleState?.classLoader = classLoader
        intent?.setExtrasClassLoader(classLoader)
        callbacks.onCreate(compatibleState) { super.onCreate(compatibleState) }
    }
    override fun onStart() = callbacks.onStart { super.onStart() }
    override fun onResume() = callbacks.onResume { super.onResume() }
    override fun onPause() = callbacks.onPause { super.onPause() }
    override fun onStop() = callbacks.onStop { super.onStop() }
    override fun onDestroy() = callbacks.onDestroy { super.onDestroy() }
    override fun onNewIntent(intent: Intent) {
        intent.setExtrasClassLoader(classLoader)
        callbacks.onNewIntent(intent) { super.onNewIntent(intent) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        val featureState = Bundle()
        callbacks.onSaveInstanceState(featureState) { super.onSaveInstanceState(featureState) }
        outState.putBundle(RUNTIME_UI_STATE_KEY, featureState)
        outState.putString(RUNTIME_STATE_KEY, RuntimeComponentRegistry.host().selectedArchiveHash())
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) = callbacks.onWindowFocusChanged(hasFocus) { super.onWindowFocusChanged(hasFocus) }
    override fun onConfigurationChanged(newConfig: Configuration) {
        RuntimeComponentRegistry.host().refreshConfiguration()
        callbacks.onConfigurationChanged(newConfig) { super.onConfigurationChanged(newConfig) }
    }
}

abstract class RuntimeAppCompatActivity : AppCompatActivity() {
    protected abstract val runtimeRole: String
    private val callbacks: ActivityCallbacks by lazy { RuntimeComponentRegistry.host().feature(runtimeRole).createActivity(runtimeRole, this) }
    override fun attachBaseContext(base: Context) { super.attachBaseContext(RuntimeComponentRegistry.host().wrap(base, runtimeRole)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        val bootstrap = RuntimeComponentRegistry.host()
        val compatibleState = savedInstanceState?.let { outer ->
            runCatching {
                outer.classLoader = javaClass.classLoader
                if (outer.getString(RUNTIME_STATE_KEY) == bootstrap.selectedArchiveHash()) outer.getBundle(RUNTIME_UI_STATE_KEY) else null
            }.getOrNull()
        }
        compatibleState?.classLoader = classLoader
        intent?.setExtrasClassLoader(classLoader)
        callbacks.onCreate(compatibleState) { super.onCreate(compatibleState) }
    }
    override fun onStart() = callbacks.onStart { super.onStart() }
    override fun onResume() = callbacks.onResume { super.onResume() }
    override fun onPause() = callbacks.onPause { super.onPause() }
    override fun onStop() = callbacks.onStop { super.onStop() }
    override fun onDestroy() = callbacks.onDestroy { super.onDestroy() }
    override fun onNewIntent(intent: Intent) {
        intent.setExtrasClassLoader(classLoader)
        callbacks.onNewIntent(intent) { super.onNewIntent(intent) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        val featureState = Bundle()
        callbacks.onSaveInstanceState(featureState) { super.onSaveInstanceState(featureState) }
        outState.putBundle(RUNTIME_UI_STATE_KEY, featureState)
        outState.putString(RUNTIME_STATE_KEY, RuntimeComponentRegistry.host().selectedArchiveHash())
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) = callbacks.onWindowFocusChanged(hasFocus) { super.onWindowFocusChanged(hasFocus) }
    override fun onConfigurationChanged(newConfig: Configuration) {
        RuntimeComponentRegistry.host().refreshConfiguration()
        callbacks.onConfigurationChanged(newConfig) { super.onConfigurationChanged(newConfig) }
    }
}

private const val RUNTIME_STATE_KEY = "chromahub.rhythm.runtime.saved_module_hash"
private const val RUNTIME_UI_STATE_KEY = "chromahub.rhythm.runtime.feature_ui_state"
