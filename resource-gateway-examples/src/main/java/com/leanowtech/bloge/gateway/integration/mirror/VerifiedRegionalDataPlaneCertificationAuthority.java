package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;

/** Independent verifier over a customer-owned current regional material source. */
public final class VerifiedRegionalDataPlaneCertificationAuthority
        implements RegionalDataPlaneCertificationAuthority {
    private final RegionalDataPlaneCertificationMaterialSource source;
    private final RegionalDataPlaneCertificationIntegrity integrity;

    /**
     * @param source customer-owned atomic current material adapter
     * @param integrity canonical and cryptographic verification boundary
     */
    public VerifiedRegionalDataPlaneCertificationAuthority(
            RegionalDataPlaneCertificationMaterialSource source,
            RegionalDataPlaneCertificationIntegrity integrity) {
        this.source = Objects.requireNonNull(source, "source");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
    }

    @Override
    public void require(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef isolationDecisionRef,
            MirrorArtifactRef isolationAttestationRef,
            Instant executionStartedAt,
            Instant executionCompletedAt) {
        if (!available()) {
            throw new TrustException("REGIONAL_CERTIFICATION_SOURCE_UNAVAILABLE");
        }
        RegionalDataPlaneCertificationMaterialSource.Current current;
        try {
            current = source.current(Objects.requireNonNull(scope, "scope"));
        } catch (TrustException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw new TrustException("REGIONAL_CERTIFICATION_SOURCE_READ_FAILED");
        }
        if (!current.isolationDecision().artifactRef().equals(isolationDecisionRef)
                || !current.isolationDecision().attestation().artifactRef()
                .equals(isolationAttestationRef)) {
            throw new TrustException("REGIONAL_ISOLATION_DECISION_DRIFTED");
        }
        RegionalDataPlaneCertificationIntegrity.VerificationResult result = integrity.verify(
                current.contract(), current.certification(), current.authorityKey(),
                current.isolationDecision(), scope, current.localDeployment(),
                executionStartedAt, executionCompletedAt);
        if (!result.verified()) {
            throw new TrustException("REGIONAL_" + result.reasonCode());
        }
    }

    @Override
    public boolean available() {
        try {
            return source.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    public Descriptor descriptor() {
        return new Descriptor("", available(), available() ? "READY" : "UNAVAILABLE",
                RegionalDataPlaneDeploymentContract.SCHEMA_VERSION,
                RegionalDataPlaneCertification.SCHEMA_VERSION);
    }
}
