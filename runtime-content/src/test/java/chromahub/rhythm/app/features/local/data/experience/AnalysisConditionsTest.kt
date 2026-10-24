// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import org.junit.Assert.*
import org.junit.Test

class AnalysisConditionsTest {
    @Test fun explicitSongAnalysisDoesNotWaitForChargingOrNinetyPercent() {
        val conditions = AnalysisConditions(false, 55, cpuBusy = true, schedulerBusy = true)
        assertTrue(conditions.allowsUserRequest())
        assertFalse(conditions.allowsWork())
        assertFalse(conditions.copy(batteryPercent = 19).allowsUserRequest())
        assertTrue(conditions.copy(batteryPercent = 20).allowsUserRequest())
        assertFalse(conditions.copy(memoryPressure = true).allowsUserRequest())
        assertFalse(conditions.copy(powerSave = true).allowsUserRequest())
        assertFalse(conditions.copy(thermalLevel = 3).allowsUserRequest())
    }
    @Test
    fun unpluggedBatteryStartsAtSeventyFivePercent() {
        assertFalse(AnalysisConditions(false, 74).allowsWork())
        assertTrue(AnalysisConditions(false, 75).allowsWork())
        assertTrue(AnalysisConditions(false, 100).allowsWork())
        assertFalse(AnalysisConditions(false, -1).allowsWork())
    }

    @Test fun customMinimumUsesInclusiveBoundariesAndClampsToTheSupportedRange() {
        assertFalse(AnalysisConditions(false, 49).allowsWork(50))
        assertTrue(AnalysisConditions(false, 50).allowsWork(50))
        assertFalse(AnalysisConditions(false, 94).allowsWork(95))
        assertTrue(AnalysisConditions(false, 95).allowsWork(95))
        assertFalse(AnalysisConditions(false, 49).allowsWork(0))
        assertTrue(AnalysisConditions(false, 95).allowsWork(100))
    }
    @Test fun loweringTheThresholdNeverBypassesHeatOrPowerSaving() {
        assertFalse(AnalysisConditions(false, 80, thermalLevel = 2).allowsWork(50))
        assertFalse(AnalysisConditions(false, 80, powerSave = true).allowsWork(50))
        assertTrue(AnalysisConditions(true, 10).allowsWork(95))
    }

    @Test
    fun ExternalPowerAllowsLowBatteryWithoutScreenIdleRequirement() {
        assertTrue(AnalysisConditions(true, 15).allowsWork())
        assertTrue(AnalysisConditions(true, -1).allowsWork())
    }

    @Test
    fun DevicePressureStopsEvenFullyChargedAnalysis() {
        val ready = AnalysisConditions(true, 100)
        assertFalse(ready.copy(thermalLevel = 2).allowsWork())
        assertFalse(ready.copy(thermalLevel = 5).allowsWork())
        assertFalse(ready.copy(cpuBusy = true).allowsWork())
        assertFalse(ready.copy(memoryPressure = true).allowsWork())
        assertFalse(ready.copy(powerSave = true).allowsWork())
        assertFalse(ready.copy(schedulerBusy = true).allowsWork())
        assertTrue(ready.copy(thermalLevel = 1).allowsWork())
    }
}
