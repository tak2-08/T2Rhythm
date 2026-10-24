// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime

data class ModuleCandidate internal constructor(val module: ModuleReference, val approved: Boolean = false)

/**
 * Immutable transition model. The host must atomically persist a returned state BEFORE executing
 * its decision, pin one version for the lifetime of the process, and serialize all transitions.
 */
data class ModuleActivationState(
    val active: ModuleReference,
    val previousGood: ModuleReference? = null,
    val candidate: ModuleCandidate? = null,
    val trialInProgress: Boolean = false,
    val highestApprovedVersion: Long = active.manifest.versionCode,
    val lastFailedArchive: String? = null,
)

data class StartupSafety(
    val freshProcess: Boolean,
    val userVisibleLaunch: Boolean,
    val playbackActive: Boolean,
    val sharingActive: Boolean,
    val backgroundWorkActive: Boolean,
) {
    internal val canActivate get() = freshProcess && userVisibleLaunch &&
        !playbackActive && !sharingActive && !backgroundWorkActive
}

sealed interface StartupDecision {
    val state: ModuleActivationState
    data class Keep(override val state: ModuleActivationState) : StartupDecision
    data class Activate(override val state: ModuleActivationState) : StartupDecision
    data class Recover(override val state: ModuleActivationState) : StartupDecision
    /** Never boot a known-incompatible old module against a changed database or revoked version. */
    data class RecoveryRequired(override val state: ModuleActivationState, val reason: Rejection) : StartupDecision
}

object ModuleActivationPolicy {
    /** Called immediately before an already crashing process exits; it never swaps its loader. */
    fun recoverReportedCrash(state: ModuleActivationState, host: HostEnvironment): ModuleActivationState {
        val fallback = state.previousGood ?: return state
        if (fallback.manifest.incompatibility(host) != null) return state
        return state.copy(active = fallback, previousGood = null, trialInProgress = false,
            candidate = state.candidate?.takeIf { it.module.manifest.incompatibility(host) == null },
            lastFailedArchive = state.active.archiveSha256)
    }

    /** Explicit offline recovery can replace broken activation metadata, without touching music. */
    fun restoreEmbeddedForRecovery(embedded: VerifiedModule, host: HostEnvironment,
        highestApprovedVersion: Long = embedded.manifest.versionCode): ModuleActivationState {
        require(embedded.manifest.incompatibility(host) == null)
        return ModuleActivationState(embedded.reference,
            highestApprovedVersion = maxOf(highestApprovedVersion, embedded.manifest.versionCode))
    }

    /** The Android-confirmed base APK may contain a newer default, or the same feature rebuilt
     * for a new fixed SDK. Never apply this method to a downloaded, unapproved candidate.
     */
    fun reconcileEmbeddedBaseUpdate(state: ModuleActivationState, embedded: VerifiedModule, host: HostEnvironment): ModuleActivationState {
        require(embedded.manifest.moduleId == state.active.manifest.moduleId)
        if (embedded.manifest.incompatibility(host) != null) return state
        val manifest = embedded.manifest
        val accepted = manifest.versionCode > state.highestApprovedVersion ||
            (manifest.versionCode == state.highestApprovedVersion &&
                manifest.runtimeFingerprint != state.active.manifest.runtimeFingerprint)
        if (!accepted) return state
        return state.copy(
            active = embedded.reference,
            previousGood = state.active.takeIf { it.manifest.incompatibility(host) == null },
            candidate = state.candidate?.takeIf { it.module.manifest.versionCode > manifest.versionCode &&
                it.module.manifest.incompatibility(host) == null },
            highestApprovedVersion = manifest.versionCode,
            trialInProgress = false,
            lastFailedArchive = null,
        )
    }

    fun stage(state: ModuleActivationState, verified: VerifiedModule): ModuleActivationState {
        check(!state.trialInProgress) { "Finish the current trial before staging another module" }
        require(verified.manifest.moduleId == state.active.manifest.moduleId)
        require(verified.manifest.versionCode > state.highestApprovedVersion) { "Older versions cannot be staged" }
        require(state.candidate == null || verified.manifest.versionCode > state.candidate.module.manifest.versionCode) {
            "A late older download cannot replace a newer candidate"
        }
        return state.copy(candidate = ModuleCandidate(verified.reference))
    }

    /** Called exclusively for the user's explicit confirmation, never by download completion. */
    fun approve(state: ModuleActivationState, archiveSha256: String): ModuleActivationState {
        val candidate = requireNotNull(state.candidate) { "No candidate" }
        require(candidate.module.archiveSha256 == archiveSha256) { "Approval belongs to another archive" }
        return state.copy(
            candidate = candidate.copy(approved = true),
            highestApprovedVersion = maxOf(state.highestApprovedVersion, candidate.module.manifest.versionCode),
        )
    }

    fun discardCandidate(state: ModuleActivationState): ModuleActivationState = state.copy(candidate = null)

    fun prepareStartup(
        state: ModuleActivationState,
        host: HostEnvironment,
        safety: StartupSafety,
    ): StartupDecision {
        // Activity recreation and reopening over a running service are not a process boundary.
        if (!safety.freshProcess) return StartupDecision.Keep(state)
        if (state.trialInProgress) {
            val fallback = state.previousGood ?: return StartupDecision.RecoveryRequired(state, Rejection.INVALID_MANIFEST)
            fallback.manifest.incompatibility(host)?.let { return StartupDecision.RecoveryRequired(state, it) }
            return StartupDecision.Recover(state.copy(
                active = fallback,
                previousGood = null,
                candidate = null,
                trialInProgress = false,
                lastFailedArchive = state.active.archiveSha256,
            ))
        }
        // Compatibility is required even when there is no update or the process is worker-started.
        state.active.manifest.incompatibility(host)?.let { return StartupDecision.RecoveryRequired(state, it) }
        val candidate = state.candidate ?: return StartupDecision.Keep(state)
        if (!candidate.approved || !safety.canActivate) return StartupDecision.Keep(state)
        candidate.module.manifest.incompatibility(host)?.let { return StartupDecision.Keep(state) }
        return StartupDecision.Activate(state.copy(
            active = candidate.module,
            previousGood = state.active,
            candidate = null,
            trialInProgress = true,
        ))
    }

    /** The host calls this only after UI and essential services have passed their startup checks. */
    fun confirmHealthy(state: ModuleActivationState, archiveSha256: String): ModuleActivationState {
        check(state.trialInProgress)
        require(state.active.archiveSha256 == archiveSha256)
        return state.copy(trialInProgress = false, lastFailedArchive = null)
    }
}
