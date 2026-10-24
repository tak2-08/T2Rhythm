// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

internal data class AnalysisConditions(
    val plugged: Boolean,
    val batteryPercent: Int,
    val thermalLevel: Int = 0,
    val powerSave: Boolean = false,
    val memoryPressure: Boolean = false,
    val cpuBusy: Boolean = false,
    val schedulerBusy: Boolean = false,
) {
    fun allowsUserRequest() =
        (plugged || batteryPercent >= 20) && thermalLevel < 3 && !powerSave && !memoryPressure

    fun allowsWork(minimumBattery: Int = 75) =
        (plugged || batteryPercent >= minimumBattery.coerceIn(50, 95)) &&
            thermalLevel < 2 &&
            !powerSave &&
            !memoryPressure &&
            !cpuBusy &&
            !schedulerBusy
}
