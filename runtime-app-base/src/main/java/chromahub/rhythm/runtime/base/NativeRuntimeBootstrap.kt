// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.base

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import chromahub.rhythm.app.BuildConfig
import chromahub.rhythm.runtime.*
import chromahub.rhythm.runtime.api.*
import chromahub.rhythm.runtime.host.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec

/** The base owns selection, storage and a single class loader for the entire process. */
class NativeRuntimeBootstrap(private val application: Application, private val androidContext: Context) : RuntimeBootstrap {
    private val root = File(androidContext.noBackupFilesDir, "native-runtime").also { check(it.mkdirs() || it.isDirectory) }
    private val archives = File(root, "archives").also { check(it.mkdirs() || it.isDirectory) }
    private val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(
        androidContext.assets.open("runtime/module-public.der").use { it.readBytes() }))
    private val verifier = RuntimePackageVerifier(listOf(publicKey))
    val environment = HostEnvironment("t2rhythm.main", BuildConfig.RUNTIME_BASE_VERSION, 1,
        BuildConfig.RUNTIME_FINGERPRINT, Build.VERSION.SDK_INT, databaseSchema())
    private val initial: VerifiedModule by lazy { installEmbedded() }
    private val store: ModuleStateStore by lazy {
        ModuleStateStore(androidContext, File(root, "activation"), initial) { hash ->
            val file = archive(hash)
            check(!Files.isSymbolicLink(file.toPath()))
            verifier.verifyAuthenticity(file)
        }
    }
    private val loader by lazy { NativeModuleTrialLoader(androidContext, verifier, environment, androidContext.classLoader) }
    @Volatile private var selected: LoadedNativeModule<RuntimeFeatureModule>? = null
    @Volatile private var resources: ModuleResourceContext? = null
    @Volatile private var callbacks: ApplicationCallbacks? = null
    @Volatile private var decision: StartupDecision? = null
    @Volatile private var failure: Throwable? = null
    private var applicationStarting = false
    private val failedModule by lazy { FailedFeatureModule(application) }
    private val updateController by lazy { NativeUpdateController(androidContext, verifier, environment, archives, { store }, { decision?.state?.active }) }
    private val isMainProcess: Boolean by lazy {
        val name = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
            (androidContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        name == androidContext.packageName
    }

    override fun feature(reason: String): RuntimeFeatureModule {
        try {
            val result = synchronized(this) {
            if (failure != null) return failedModule
            if (selected == null) {
                val safety = StartupSafety(true, isMainProcess && reason in setOf("main", "external", "selector"),
                    false, false, false)
                val startup = if (isMainProcess) store.prepareStartup(environment, safety) else {
                    // A separate crash UI must not consume the primary process's unfinished trial.
                    val snapshot = store.snapshot()
                    val readOnly = if (snapshot.trialInProgress) snapshot.copy(active = requireNotNull(snapshot.previousGood)) else snapshot
                    ModuleActivationPolicy.prepareStartup(readOnly.copy(trialInProgress = false), environment,
                        StartupSafety(true, false, false, false, false))
                }
                check(startup !is StartupDecision.RecoveryRequired) { "No compatible native module" }
                decision = startup
                val loaded = loader.loadStartup(startup, archive(startup.state.active.archiveSha256), RuntimeFeatureModule::class.java)
                check(loaded.entry.describe().getLong("compiledVersionCode") == loaded.reference.manifest.versionCode) {
                    "Feature bytecode and signed version metadata differ"
                }
                selected = loaded
                resources = ModuleResourceContext.attach(androidContext, loaded)
            }
            requireNotNull(selected).entry
            }
            // Application initialization retains Android's main-thread lifecycle contract, even
            // when WorkManager or an image request is the first consumer of the module.
            if (reason != "crash" && isMainProcess) application(true)
            return result
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            failure = error
            callbacks = null
            Log.e("NativeRuntime", "Native startup failed; leaving the trial journal intact for recovery", error)
            return failedModule
        }
    }

    override fun wrap(context: Context, reason: String): Context {
        val module = feature(reason)
        return if (module === failedModule) context else ModuleResourceContext.attach(context, requireNotNull(selected))
    }

    override fun application(initialize: Boolean): ApplicationCallbacks? {
        callbacks?.let { return it }
        if (!initialize) return null
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val task = java.util.concurrent.FutureTask<ApplicationCallbacks?> { application(true) }
            check(Handler(Looper.getMainLooper()).post(task))
            return task.get(60, java.util.concurrent.TimeUnit.SECONDS)
        }
        callbacks?.let { return it }
        if (applicationStarting) return callbacks
        if (failure != null) return failedModule.createApplication(application).also { callbacks = it }
        if (selected == null) feature("application")
        callbacks?.let { return it }
        if (failure != null) return failedModule.createApplication(application).also { callbacks = it }
        applicationStarting = true
        try {
            val value = requireNotNull(selected).entry.createApplication(application)
            callbacks = value
            value.onCreate { } // Android's actual Application.onCreate already ran once.
            val featureHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                reportFatalError()
                featureHandler?.uncaughtException(thread, error)
            }
            return value
        } finally { applicationStarting = false }
    }

    override fun applicationResources(fallback: Resources): Resources = if (failure == null) resources?.resources ?: fallback else fallback
    override fun applicationClassLoader(fallback: ClassLoader): ClassLoader = if (failure == null) selected?.entry?.javaClass?.classLoader ?: fallback else fallback
    override fun applicationConfigurationContext(fallback: Context): Context = selected?.let { ModuleResourceContext.attach(fallback, it) } ?: fallback

    override fun workerFactory(): Any = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
            if (!workerClassName.startsWith("chromahub.rhythm.app.")) return null
            val module = feature("worker")
            return module.createWorker(wrap(appContext, "worker"), workerClassName, workerParameters) as ListenableWorker
        }
    }
    override fun updates(): RuntimeUpdateController = updateController
    override fun selectedArchiveHash(): String = selected?.reference?.archiveSha256.orEmpty()
    override fun refreshConfiguration() { ModuleResourceContext.refreshAll() }
    private fun reportFatalError() {
        if (!isMainProcess) return
        val process = selected ?: return
        runCatching { store.reportFatalError(process.reference.archiveSha256, environment) }
            .onFailure { Log.e("NativeRuntime", "Unable to record crash recovery", it) }
    }

    internal fun restoreEmbeddedForRecovery(): Boolean {
        if (!isMainProcess || selected != null && failure == null) return false
        return runCatching {
            installEmbedded() // Reverify/recreate the offline archive before committing its reference.
            store.restoreEmbedded(environment)
        }.isSuccess
    }

    override fun reportUiReady() {
        if (!isMainProcess) return
        // Wait through actual frame/initialization work before declaring a downloaded trial healthy.
        Handler(Looper.getMainLooper()).postDelayed({
            val activation = synchronized(this) { decision as? StartupDecision.Activate }
            if (activation != null && failure == null) {
                java.util.concurrent.Executors.newSingleThreadExecutor().apply {
                    execute {
                        try {
                            val healthy = store.confirmHealthy(activation.state.active.archiveSha256)
                            synchronized(this@NativeRuntimeBootstrap) { decision = StartupDecision.Keep(healthy) }
                            updateController.notifyChanged()
                        } catch (error: Exception) { Log.e("NativeRuntime", "Unable to commit healthy startup", error) }
                        finally { shutdown() }
                    }
                }
            }
        }, 5000)
    }

    private fun archive(hash: String): File {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        return File(archives, "$hash.t2module")
    }

    private fun installEmbedded(): VerifiedModule {
        val temporary = File.createTempFile("embedded-", ".t2module", archives)
        try {
            FileOutputStream(temporary).use { output ->
                check(temporary.setReadOnly())
                androidContext.assets.open("runtime/default.t2module").use { input ->
                    val bytes = ByteArray(65536)
                    var size = 0L
                    while (true) {
                        val count = input.read(bytes)
                        if (count == -1) break
                        size += count; check(size <= 224L * 1024 * 1024)
                        output.write(bytes, 0, count)
                    }
                }
                output.fd.sync()
            }
            val verified = verifier.verify(temporary, environment)
            val destination = archive(verified.archiveSha256)
            withArchiveLock(archives) {
                if (destination.exists()) {
                    check(!Files.isSymbolicLink(destination.toPath()))
                    check(verifier.verify(destination, environment).reference == verified.reference)
                } else {
                    check(temporary.renameTo(destination))
                    syncRuntimeDirectory(archives)
                }
            }
            return verified
        } finally { temporary.delete() }
    }

    internal fun failureDescription(): String = failure?.javaClass?.simpleName ?: "Native module unavailable"

    private fun databaseSchema(): Int {
        val database = androidContext.getDatabasePath("rhythm_database")
        if (!database.exists()) return 9
        return runCatching {
            android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { it.version.takeIf { value -> value > 0 } ?: 9 }
        }.getOrDefault(0)
    }
}
