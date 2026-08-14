package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;

/**
 * Composes regional certification with the existing deployment-isolation run trust.
 *
 * <p>The existing isolation agent still owns the stable decision and commit permit. Its v2
 * decision content-addresses the regional certification, so the same decision reference signed
 * into Mirror evidence closes the regional proof without inventing a second run lifecycle.</p>
 */
public final class RegionalDataPlaneCertifiedRunTrustAuthority
        implements MirrorDeploymentIsolationRunTrustAuthority {
    private final MirrorDeploymentIsolationRunTrustAuthority isolation;
    private final RegionalDataPlaneCertificationAuthority regional;

    /**
     * @param isolation existing deployment-isolation authority
     * @param regional regional contract and certification authority
     */
    public RegionalDataPlaneCertifiedRunTrustAuthority(
            MirrorDeploymentIsolationRunTrustAuthority isolation,
            RegionalDataPlaneCertificationAuthority regional) {
        this.isolation = Objects.requireNonNull(isolation, "isolation");
        this.regional = Objects.requireNonNull(regional, "regional");
    }

    @Override
    public MirrorDeploymentIsolationRunTrust.Admission admit(
            CapabilitySnapshot.Scope scope) {
        MirrorDeploymentIsolationRunTrust.Admission admitted = isolation.admit(scope);
        require(admitted, admitted.admittedAt(), admitted.admittedAt());
        return admitted;
    }

    @Override
    public MirrorDeploymentIsolationRunTrust.Binding confirm(
            MirrorDeploymentIsolationRunTrust.Admission admission,
            Instant startedAt,
            Instant completedAt) {
        MirrorDeploymentIsolationRunTrust.Binding binding = isolation.confirm(
                admission, startedAt, completedAt);
        require(admission.scope(), binding.decisionRef(), binding.attestationRef(),
                startedAt, completedAt);
        return binding;
    }

    @Override
    public CommitPermit acquireCommitPermit(
            CapabilitySnapshot.Scope scope,
            MirrorDeploymentIsolationRunTrust.Binding binding) {
        CommitPermit isolationPermit = isolation.acquireCommitPermit(scope, binding);
        try {
            require(scope, binding.decisionRef(), binding.attestationRef(),
                    binding.admittedAt(), binding.confirmedAt());
            return isolationPermit;
        } catch (RuntimeException denied) {
            isolationPermit.close();
            throw denied;
        }
    }

    @Override
    public boolean available() {
        return isolation.available() && regional.available();
    }

    private void require(
            MirrorDeploymentIsolationRunTrust.Admission admission,
            Instant start,
            Instant end) {
        require(admission.scope(), admission.decisionRef(), admission.attestationRef(),
                start, end);
    }

    private void require(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef decisionRef,
            MirrorArtifactRef attestationRef,
            Instant start,
            Instant end) {
        try {
            regional.require(scope, decisionRef, attestationRef, start, end);
        } catch (RegionalDataPlaneCertificationAuthority.TrustException denied) {
            throw new TrustException(denied.reasonCode());
        }
    }
}
