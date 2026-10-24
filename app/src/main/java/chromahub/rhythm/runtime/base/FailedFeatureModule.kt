// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.base

import chromahub.rhythm.app.R

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.*
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.text.Text
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.work.Worker
import androidx.work.WorkerParameters
import chromahub.rhythm.runtime.api.*
import chromahub.rhythm.runtime.components.MediaServiceCallbacks
import java.io.FileNotFoundException

/** No classes from a failed feature are instantiated again in this process. */
internal class FailedFeatureModule(private val app: Application) : RuntimeFeatureModule {
    override fun describe() = Bundle().apply { putBoolean("failed", true) }
    override fun createApplication(host: Application) = object : ApplicationCallbacks {
        override fun onCreate(parent: Runnable) = parent.run()
        override fun newImageLoader(): Any = coil.ImageLoader.Builder(app).diskCache(null).build()
    }
    override fun createActivity(role: String, host: Activity) = object : ActivityCallbacks {
        override fun onCreate(state: Bundle?, parent: Runnable) {
            parent.run()
            host.startActivity(Intent(host, RuntimeRecoveryActivity::class.java))
            host.finish()
        }
    }
    override fun createService(role: String, host: Service): ServiceCallbacks = object : MediaServiceCallbacks {
        override fun onCreate(parent: Runnable) { parent.run(); host.stopSelf() }
        override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? = null
        override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean, parent: Runnable) { }
    }
    override fun createReceiver(role: String) = ReceiverCallbacks { _, _, _ -> }
    override fun createArtworkProvider(owner: ContentProvider): ContentProvider = object : ContentProvider() {
        override fun onCreate() = false
        override fun getType(uri: Uri) = "image/jpeg"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = throw FileNotFoundException("Native recovery required")
    }
    override fun createWidget(role: String): Any = object : GlanceAppWidget() {
        override suspend fun provideGlance(context: Context, id: androidx.glance.GlanceId) {
            provideContent { Text(app.baseContext.getString(R.string.runtime_widget_recovery)) }
        }
    }
    override fun createWidgetAction(className: String): Any = object : ActionCallback {
        override suspend fun onAction(context: Context, glanceId: androidx.glance.GlanceId, parameters: ActionParameters) {
            context.startActivity(Intent(context, RuntimeRecoveryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    override fun createWorker(context: Context, className: String, params: Any): Any =
        RuntimeRecoveryWorker(context, params as WorkerParameters)
    override fun performServiceAction(role: String, action: String, context: Context) { }
}

/** Work retries while the verified feature is recovered, retaining the existing retry limit. */
class RuntimeRecoveryWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork() = if (runAttemptCount < 3) Result.retry() else Result.failure()
}
