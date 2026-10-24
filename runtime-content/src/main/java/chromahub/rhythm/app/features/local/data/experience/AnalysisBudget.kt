// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay

internal class AnalysisSliceEnded : Exception("Analysis slice completed")

internal class AnalysisDeferred(conditions: AnalysisConditions? = null) :
    Exception("Analysis waits for sufficient power and low device load" + (conditions?.let { ": $it" } ?: ""))

/** One low-priority analysis thread app-wide, with short CPU bursts and cooperative cooling. */
internal class AnalysisBudget(
    private val context: Context,
    private val active: () -> Boolean = { true },
    private val maxMillis: Long = Long.MAX_VALUE,
    private val userInitiated: Boolean = false,
) {
    private val startedAt = SystemClock.elapsedRealtime()
    private var sliceStarted = SystemClock.elapsedRealtime()
    private var checkedAt = -1000L

    fun allowed(): Boolean = accepts(DeviceAnalysisLoad.conditions(context))

    private fun accepts(conditions: AnalysisConditions) =
        if (userInitiated) conditions.allowsUserRequest() else conditions.allowsWork(
            chromahub.rhythm.app.shared.data.model.AppSettings.getInstance(context).analysisMinimumBattery.value)

    private fun requireAllowed() {
        val conditions = DeviceAnalysisLoad.conditions(context)
        if (!accepts(conditions)) throw AnalysisDeferred(conditions)
    }

    suspend fun checkpoint() {
        if (!active()) throw kotlinx.coroutines.CancellationException("Analysis stopped")
        val now = SystemClock.elapsedRealtime()
        if (now - startedAt >= maxMillis) throw AnalysisSliceEnded()
        if (now - checkedAt >= 1000) {
            checkedAt = now
            requireAllowed()
        }
        if (now - sliceStarted >= 16) {
            val before = SystemClock.elapsedRealtime()
            delay(if (userInitiated) 16 else 144)
            sliceStarted = SystemClock.elapsedRealtime()
            if (!userInitiated && sliceStarted - before > 700) {
                DeviceAnalysisLoad.pressure()
                throw AnalysisDeferred()
            }
        }
    }

    fun blockingCheckpoint() {
        if (!active()) throw kotlinx.coroutines.CancellationException("Analysis stopped")
        val now = SystemClock.elapsedRealtime()
        if (now - startedAt >= maxMillis) throw AnalysisSliceEnded()
        if (now - checkedAt >= 1000) {
            checkedAt = now
            requireAllowed()
        }
        if (now - sliceStarted >= 16) {
            val before = SystemClock.elapsedRealtime()
            Thread.sleep(if (userInitiated) 16 else 144)
            sliceStarted = SystemClock.elapsedRealtime()
            if (!userInitiated && sliceStarted - before > 700) {
                DeviceAnalysisLoad.pressure()
                throw AnalysisDeferred()
            }
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    companion object {
        val dispatcher =
            Executors.newSingleThreadExecutor { work ->
                    Thread(
                            {
                                android.os.Process.setThreadPriority(
                                    android.os.Process.THREAD_PRIORITY_LOWEST
                                )
                                work.run()
                            },
                            "T2Rhythm-analysis",
                        )
                        .apply { isDaemon = true }
                }
                .asCoroutineDispatcher()
    }
}

/** Cheap shared samples; no location, microphone, usage-access permission or stored telemetry. */
internal object DeviceAnalysisLoad {
    private var sampledAt = 0L
    private var previousCpu = 0L
    private var appBusy = false
    private var cpuTotals: Pair<Long, Long>? = null
    private var systemBusy = false
    private var procReadable = true
    private var systemSampledAt = 0L
    @Volatile private var pressuredUntil = 0L

    fun pressure() {
        pressuredUntil = SystemClock.elapsedRealtime() + 15_000
    }

    @Synchronized
    fun conditions(context: Context): AnalysisConditions {
        val now = SystemClock.elapsedRealtime()
        if (sampledAt == 0L || now - sampledAt >= 2_000) {
            val cpu = android.os.Process.getElapsedCpuTime()
            appBusy = sampledAt != 0L && (cpu - previousCpu).toDouble() / (now - sampledAt) > .8
            previousCpu = cpu
            sampledAt = now
        }
        if (procReadable && now - systemSampledAt >= 5_000) {
            systemSampledAt = now
            try {
                val fields =
                    java.io
                        .File("/proc/stat")
                        .bufferedReader()
                        .use { it.readLine() }
                        .trim()
                        .split(Regex("\\s+"))
                        .drop(1)
                        .take(8)
                        .map(String::toLong)
                val next = fields.sum() to (fields[3] + fields.getOrElse(4) { 0L })
                cpuTotals?.let { old ->
                    val total = next.first - old.first
                    if (total > 0)
                        systemBusy = 1.0 - (next.second - old.second).toDouble() / total > .7
                }
                cpuTotals = next
            } catch (_: Exception) {
                procReadable = false
                systemBusy = false
            }
        }
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val status =
            context.registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
            )
        val plugged =
            status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 }
                ?: battery.isCharging
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent =
            if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100)
            else battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val memory = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .getMemoryInfo(memory)
        val runtime = Runtime.getRuntime()
        val heapFree = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        val heapPressure = heapFree < minOf(64L * 1024 * 1024, runtime.maxMemory() / 4)
        return AnalysisConditions(
            plugged,
            percent,
            if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else 0,
            power.isPowerSaveMode,
            heapPressure ||
                memory.lowMemory ||
                memory.availMem < maxOf(memory.threshold, 128L * 1024 * 1024),
            appBusy || systemBusy,
            now < pressuredUntil,
        )
    }
}
