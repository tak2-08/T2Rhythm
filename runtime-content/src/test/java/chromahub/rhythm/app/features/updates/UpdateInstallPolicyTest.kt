// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.updates

import org.junit.Assert.*
import org.junit.Test

class UpdateInstallPolicyTest {
    @Test fun replacementConfirmsTheExactRequestedVersion() { assertTrue(UpdateInstallPolicy.confirmed(124, 123, 124, 10_000, 12_000)) }
    @Test fun installerLaunchWithoutReplacementDoesNotConfirm() { assertFalse(UpdateInstallPolicy.confirmed(124, 123, 123, 10_000, 1_000)) }
    @Test fun cancelledFutureUpdateIsNotInstalled() { assertFalse(UpdateInstallPolicy.confirmed(125, 123, 124, 10_000, 12_000)) }
    @Test fun skippedVersionIsNotInventedAsAnInstalledUpdate() { assertFalse(UpdateInstallPolicy.confirmed(124, 123, 125, 10_000, 12_000)) }
    @Test fun olderInstallTimestampCannotConfirmAnInstallerAttempt() { assertFalse(UpdateInstallPolicy.confirmed(124, 123, 124, 10_000, 1_000)) }
    @Test fun sameVersionDownloadIsNotAnUpgrade() { assertFalse(UpdateInstallPolicy.confirmed(124, 124, 124, 10_000, 12_000)) }
}
