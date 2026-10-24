// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModuleActivationPolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val policy = ModuleActivationPolicy
    private val host = PackageFixture.host
    private val safe = StartupSafety(true, true, false, false, false)
    private fun module(version: Long) = PackageFixture.verified(temporary.root, version)
    private fun initial() = ModuleActivationState(module(1).reference)
    private fun approved() = initial().let { state ->
        val candidate = module(2)
        policy.approve(policy.stage(state, candidate), candidate.archiveSha256)
    }

    @Test fun downloadingDoesNotApproveOrActivateCode() {
        val state = policy.stage(initial(), module(2))
        assertFalse(state.candidate!!.approved)
        assertTrue(policy.prepareStartup(state, host, safe) is StartupDecision.Keep)
        assertEquals(1L, state.active.manifest.versionCode)
    }

    @Test fun approvalBelongsToExactVerifiedArchive() {
        val candidate = module(2)
        val state = policy.stage(initial(), candidate)
        assertThrows(IllegalArgumentException::class.java) { policy.approve(state, "f".repeat(64)) }
        assertEquals(candidate.archiveSha256, policy.approve(state, candidate.archiveSha256).candidate!!.module.archiveSha256)
    }

    @Test fun replacingCandidateRequiresNewConfirmation() {
        val before = approved()
        val after = policy.stage(before, module(3))
        assertFalse(after.candidate!!.approved)
        assertThrows(IllegalArgumentException::class.java) { policy.approve(after, before.candidate!!.module.archiveSha256) }
    }

    @Test fun reopeningActivityCannotActivateDownloadedClasses() {
        val state = approved()
        assertEquals(StartupDecision.Keep(state), policy.prepareStartup(state, host, safe.copy(freshProcess = false)))
    }

    @Test fun ongoingPlaybackDefersAnApprovedUpdate() {
        val state = approved()
        assertEquals(StartupDecision.Keep(state), policy.prepareStartup(state, host, safe.copy(playbackActive = true)))
        assertTrue(state.candidate!!.approved)
    }

    @Test fun activeSharingDefersAnApprovedUpdate() {
        val state = approved()
        assertEquals(StartupDecision.Keep(state), policy.prepareStartup(state, host, safe.copy(sharingActive = true)))
    }

    @Test fun runningBackgroundWorkDefersAnApprovedUpdate() {
        val state = approved()
        assertEquals(StartupDecision.Keep(state), policy.prepareStartup(state, host, safe.copy(backgroundWorkActive = true)))
    }

    @Test fun workerStartedProcessDoesNotApplyAnUpdate() {
        val state = approved()
        assertEquals(StartupDecision.Keep(state), policy.prepareStartup(state, host, safe.copy(userVisibleLaunch = false)))
    }

    @Test fun safeColdStartBeginsTrialAndRetainsPreviousVersion() {
        val before = approved()
        val decision = policy.prepareStartup(before, host, safe) as StartupDecision.Activate
        assertEquals(2L, decision.state.active.manifest.versionCode)
        assertEquals(before.active, decision.state.previousGood)
        assertTrue(decision.state.trialInProgress)
        assertNull(decision.state.candidate)
    }

    @Test fun incompatibleCandidateIsRecheckedAndLeftPending() {
        val manifest = PackageFixture.manifest().apply { this["maxDataSchema"] = 1 }
        val verifier = RuntimePackageVerifier(listOf(PackageFixture.signingKey.public))
        val candidate = verifier.verify(PackageFixture.archive(temporary.root, PackageFixture.entries(manifest)), host)
        val before = policy.approve(policy.stage(initial(), candidate), candidate.archiveSha256)
        assertEquals(StartupDecision.Keep(before), policy.prepareStartup(before, host.copy(dataSchema = 2), safe))
    }

    @Test fun healthyTrialRemainsActiveOnNextLaunch() {
        val trial = policy.prepareStartup(approved(), host, safe).state
        val healthy = policy.confirmHealthy(trial, trial.active.archiveSha256)
        assertFalse(healthy.trialInProgress)
        assertEquals(StartupDecision.Keep(healthy), policy.prepareStartup(healthy, host, safe))
    }

    @Test fun anotherArchiveCannotMarkTrialHealthy() {
        val trial = policy.prepareStartup(approved(), host, safe).state
        assertThrows(IllegalArgumentException::class.java) { policy.confirmHealthy(trial, "f".repeat(64)) }
        assertTrue(trial.trialInProgress)
    }

    @Test fun interruptedTrialRecoversPreviousGoodVersion() {
        val trial = policy.prepareStartup(approved(), host, safe).state
        val recovery = policy.prepareStartup(trial, host, safe) as StartupDecision.Recover
        assertEquals(1L, recovery.state.active.manifest.versionCode)
        assertFalse(recovery.state.trialInProgress)
        assertEquals(trial.active.archiveSha256, recovery.state.lastFailedArchive)
        assertNull(recovery.state.candidate)
        assertEquals(2L, recovery.state.highestApprovedVersion)
    }

    @Test fun sameProcessCannotMistakeAnOngoingTrialForACrash() {
        val trial = policy.prepareStartup(approved(), host, safe).state
        assertEquals(StartupDecision.Keep(trial), policy.prepareStartup(trial, host, safe.copy(freshProcess = false)))
    }

    @Test fun incompatibleDatabaseBlocksAutomaticRollback() {
        val trial = policy.prepareStartup(approved(), host, safe).state
        val decision = policy.prepareStartup(trial, host.copy(dataSchema = 4), safe) as StartupDecision.RecoveryRequired
        assertEquals(Rejection.DATA_SCHEMA, decision.reason)
        assertEquals(trial, decision.state)
    }

    @Test fun revokedFallbackCannotRunDuringRecovery() {
        val trial = policy.prepareStartup(approved(), host, safe).state
        val decision = policy.prepareStartup(trial, host.copy(minimumModuleVersion = 2), safe) as StartupDecision.RecoveryRequired
        assertEquals(Rejection.VERSION_REVOKED, decision.reason)
    }

    @Test fun oldCandidateCannotDowngradeHighestApprovedVersion() {
        val state = initial().copy(highestApprovedVersion = 3)
        assertThrows(IllegalArgumentException::class.java) { policy.stage(state, module(2)) }
        assertThrows(IllegalArgumentException::class.java) { policy.stage(state, module(1)) }
    }

    @Test fun failedVersionIsNotAutomaticallyRestagedInACrashLoop() {
        val trial = policy.prepareStartup(approved(), host, safe).state
        val recovered = policy.prepareStartup(trial, host, safe).state
        assertThrows(IllegalArgumentException::class.java) { policy.stage(recovered, module(2)) }
        assertNotNull(policy.stage(recovered, module(3)).candidate)
    }

    @Test fun cancellingPendingUpdatePreservesCurrentModule() {
        val state = approved()
        val cancelled = policy.discardCandidate(state)
        assertEquals(state.active, cancelled.active)
        assertNull(cancelled.candidate)
        assertEquals(StartupDecision.Keep(cancelled), policy.prepareStartup(cancelled, host, safe))
    }

    @Test fun lateOlderDownloadCannotReplaceANewerPendingCandidate() {
        val state = policy.stage(initial(), module(3))
        assertThrows(IllegalArgumentException::class.java) { policy.stage(state, module(2)) }
        assertEquals(3L, state.candidate!!.module.manifest.versionCode)
    }

    @Test fun anotherUpdateCannotReplaceAnUnfinishedTrial() {
        val trial = policy.prepareStartup(approved(), host, safe).state
        assertThrows(IllegalStateException::class.java) { policy.stage(trial, module(3)) }
    }

    @Test fun incompatibleActiveVersionRequiresRecoveryInsteadOfBlindBoot() {
        val decision = policy.prepareStartup(initial(), host.copy(hostApi = 2), safe) as StartupDecision.RecoveryRequired
        assertEquals(Rejection.HOST_API, decision.reason)
    }
}
