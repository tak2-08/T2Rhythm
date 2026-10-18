// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Real load protection remains enabled: retry an isolated fixture only after the device cools. */
class AnalysisRetryRule : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val deadline = SystemClock.elapsedRealtime() + 120_000
            while (true) {
                try {
                    base.evaluate()
                    return
                } catch (deferred: AnalysisDeferred) {
                    if (SystemClock.elapsedRealtime() >= deadline) throw deferred
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    do {
                        Thread.sleep(2500)
                    } while (!AnalysisBudget(context).allowed() && SystemClock.elapsedRealtime() < deadline)
                }
            }
        }
    }
}
