// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import org.junit.Assert.*
import org.junit.Test

class AnalysisConditionsTest {
    @Test
    fun unpluggedBatteryStartsAtNinetyPercent() {
        assertFalse(AnalysisConditions(false, 89).allowsWork())
        assertTrue(AnalysisConditions(false, 90).allowsWork())
        assertTrue(AnalysisConditions(false, 100).allowsWork())
        assertFalse(AnalysisConditions(false, -1).allowsWork())
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
