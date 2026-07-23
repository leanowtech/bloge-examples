package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Binds certifiable Mirror execution to double-observed deployment-agent trust.
 *
 * <p>Admission and confirmation may observe different local cache generations only when their
 * atomic attestation-bundle decision is identical. Commit acquires the agent read lock so a local
 * revocation or trust transition linearizes either before or after the evidence transaction.</p>
 */
public final class AgentBackedMirrorDeploymentIsolationRunTrustAuthority
        implements MirrorDeploymentIsolationRunTrustAuthority {
    private final MirrorDeploymentIsolationTrustAgent agent;
    private final Clock clock;

    /**
     * @param agent deployment-owned verified trust cache
     * @param clock trusted runtime clock shared with execution admission
     */
    public AgentBackedMirrorDeploymentIsolationRunTrustAuthority(
            MirrorDeploymentIsolationTrustAgent agent, Clock clock) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public MirrorDeploymentIsolationRunTrust.Admission admit(
            CapabilitySnapshot.Scope scope) {
        Instant admittedAt = clock.instant();
        try (var permit = agent.acquireActiveSnapshot(scope, admittedAt)) {
            MirrorDeploymentIsolationAgentSnapshot snapshot = permit.snapshot();
            MirrorDeploymentIsolationAttestationBundle bundle = snapshot.attestationBundle();
            requireCoverage(snapshot, admittedAt, admittedAt);
            return new MirrorDeploymentIsolationRunTrust.Admission(
                    scope, bundle.artifactRef(), bundle.authorityKeySetRef(),
                    bundle.attestation().artifactRef(), bundle.status().artifactRef(),
                    snapshot.artifactRef(), admittedAt, snapshot.validUntil());
        } catch (MirrorDeploymentIsolationTrustAgent.TrustUnavailableException denied) {
            throw denied("RUN_TRUST_ADMISSION_UNAVAILABLE");
        } catch (RuntimeException invalid) {
            throw denied("RUN_TRUST_ADMISSION_INVALID");
        }
    }

    @Override
    public MirrorDeploymentIsolationRunTrust.Binding confirm(
            MirrorDeploymentIsolationRunTrust.Admission admission,
            Instant startedAt,
            Instant completedAt) {
        MirrorDeploymentIsolationRunTrust.Admission exact = Objects.requireNonNull(
                admission, "admission");
        Instant started = Objects.requireNonNull(startedAt, "startedAt");
        Instant completed = Objects.requireNonNull(completedAt, "completedAt");
        if (completed.isBefore(started)) {
            throw denied("RUN_TRUST_EXECUTION_WINDOW_INVALID");
        }
        Instant confirmedAt = clock.instant();
        try (var permit = agent.acquireActiveSnapshot(exact.scope(), confirmedAt)) {
            MirrorDeploymentIsolationAgentSnapshot snapshot = permit.snapshot();
            requireSameDecision(exact, snapshot);
            requireCoverage(snapshot, started, completed);
            if (snapshot.cacheGeneration() < exact.admittedSnapshotRef().revision()
                    || confirmedAt.isBefore(completed)) {
                throw denied("RUN_TRUST_CONFIRMATION_INVALID");
            }
            MirrorDeploymentIsolationAttestationBundle bundle = snapshot.attestationBundle();
            return new MirrorDeploymentIsolationRunTrust.Binding("",
                    bundle.artifactRef(), bundle.authorityKeySetRef(),
                    bundle.attestation().artifactRef(), bundle.status().artifactRef(),
                    exact.admittedSnapshotRef(), snapshot.artifactRef(),
                    exact.admittedAt(), confirmedAt);
        } catch (MirrorDeploymentIsolationRunTrustAuthority.TrustException denied) {
            throw denied;
        } catch (MirrorDeploymentIsolationTrustAgent.TrustUnavailableException denied) {
            throw denied("RUN_TRUST_CONFIRMATION_UNAVAILABLE");
        } catch (RuntimeException invalid) {
            throw denied("RUN_TRUST_CONFIRMATION_INVALID");
        }
    }

    @Override
    public CommitPermit acquireCommitPermit(
            CapabilitySnapshot.Scope scope,
            MirrorDeploymentIsolationRunTrust.Binding binding) {
        CapabilitySnapshot.Scope exactScope = Objects.requireNonNull(scope, "scope");
        MirrorDeploymentIsolationRunTrust.Binding exactBinding = Objects.requireNonNull(
                binding, "binding");
        MirrorDeploymentIsolationTrustAgent.ActiveSnapshotPermit permit = null;
        try {
            permit = agent.acquireActiveSnapshot(exactScope, clock.instant());
            MirrorDeploymentIsolationAgentSnapshot snapshot = permit.snapshot();
            requireSameDecision(exactBinding, snapshot);
            if (snapshot.cacheGeneration() < exactBinding.committedSnapshotRef().revision()) {
                throw denied("RUN_TRUST_CACHE_ROLLBACK");
            }
            MirrorDeploymentIsolationTrustAgent.ActiveSnapshotPermit held = permit;
            return held::close;
        } catch (MirrorDeploymentIsolationRunTrustAuthority.TrustException denied) {
            close(permit);
            throw denied;
        } catch (RuntimeException invalid) {
            close(permit);
            throw denied("RUN_TRUST_COMMIT_UNAVAILABLE");
        }
    }

    @Override
    public boolean available() {
        try {
            return agent.observation().available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static void requireSameDecision(
            MirrorDeploymentIsolationRunTrust.Admission admission,
            MirrorDeploymentIsolationAgentSnapshot snapshot) {
        MirrorDeploymentIsolationAttestationBundle bundle = snapshot.attestationBundle();
        if (!admission.scope().equals(bundle.scope())
                || !admission.decisionRef().equals(bundle.artifactRef())
                || !admission.authorityKeySetRef().equals(bundle.authorityKeySetRef())
                || !admission.attestationRef().equals(bundle.attestation().artifactRef())
                || !admission.statusRef().equals(bundle.status().artifactRef())) {
            throw denied("RUN_TRUST_DECISION_CHANGED");
        }
    }

    private static void requireSameDecision(
            MirrorDeploymentIsolationRunTrust.Binding binding,
            MirrorDeploymentIsolationAgentSnapshot snapshot) {
        MirrorDeploymentIsolationAttestationBundle bundle = snapshot.attestationBundle();
        if (!binding.decisionRef().equals(bundle.artifactRef())
                || !binding.authorityKeySetRef().equals(bundle.authorityKeySetRef())
                || !binding.attestationRef().equals(bundle.attestation().artifactRef())
                || !binding.statusRef().equals(bundle.status().artifactRef())) {
            throw denied("RUN_TRUST_DECISION_CHANGED");
        }
    }

    private static void requireCoverage(
            MirrorDeploymentIsolationAgentSnapshot snapshot,
            Instant startedAt,
            Instant completedAt) {
        MirrorDeploymentIsolationAttestation.Material attestation =
                snapshot.attestationBundle().attestation().material();
        MirrorDeploymentIsolationAuthorityKeySetPublication.Material authority =
                Objects.requireNonNull(snapshot.authorityPublication(),
                        "authorityPublication").material();
        Instant statusEffectiveAt = snapshot.attestationBundle().status().material().effectiveAt();
        if (startedAt.isBefore(attestation.validFrom())
                || !completedAt.isBefore(attestation.expiresAt())
                || startedAt.isBefore(authority.notBefore())
                || !completedAt.isBefore(authority.expiresAt())
                || startedAt.isBefore(statusEffectiveAt)) {
            throw denied("RUN_TRUST_EXECUTION_WINDOW_UNCOVERED");
        }
    }

    private static void close(
            MirrorDeploymentIsolationTrustAgent.ActiveSnapshotPermit permit) {
        if (permit != null) {
            permit.close();
        }
    }

    private static TrustException denied(String reasonCode) {
        return new TrustException(reasonCode);
    }
}
