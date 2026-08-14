package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class RegionalDataPlaneCertificationIntegrityTest {
    private final RegionalDataPlaneCertificationTestFixtures fixtures =
            new RegionalDataPlaneCertificationTestFixtures();

    @Test
    void verifiesCompleteFreshRegionalCertificationBoundToIsolationDecision() {
        var result = verify(fixtures.certification, fixtures.isolationV2);

        assertThat(result.verified()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("VERIFIED");
        assertThat(fixtures.integrity.canonicalContractVerified(fixtures.contract)).isTrue();
        assertThat(fixtures.integrity.canonicalCertificationVerified(
                fixtures.certification)).isTrue();
        assertThat(fixtures.isolationFixtures.bundleIntegrity.canonicalBundleVerified(
                fixtures.isolationV2)).isTrue();
        assertThat(fixtures.isolationV2.schemaVersion()).isEqualTo(
                MirrorDeploymentIsolationAttestationBundle
                        .REGIONAL_DATA_PLANE_SCHEMA_VERSION);
    }

    @Test
    void rejectsStaleOrDegradedComponentWithoutAggregateScoring() {
        var stale = fixtures.certification(
                fixtures.observations(fixtures.now.minus(Duration.ofMinutes(2)), null),
                fixtures.rotations(fixtures.now.minusSeconds(3), null), 0, 0);
        var degraded = fixtures.certification(
                fixtures.observations(fixtures.now.minusSeconds(2),
                        RegionalDataPlaneDeploymentContract.ComponentKind.PAYLOAD_VAULT),
                fixtures.rotations(fixtures.now.minusSeconds(3), null), 0, 0);

        assertThat(verify(stale, isolation(stale)).reasonCode())
                .isEqualTo("COMPONENT_OBSERVATION_STALE");
        assertThat(verify(degraded, isolation(degraded)).reasonCode())
                .isEqualTo("COMPONENT_CONTROL_NOT_READY");
    }

    @Test
    void rejectsKmsOrCaRotationThatDidNotConverge() {
        var failed = fixtures.certification(
                fixtures.observations(fixtures.now.minusSeconds(2), null),
                fixtures.rotations(fixtures.now.minusSeconds(3),
                        RegionalDataPlaneCertification.RotationKind.MUTUAL_TLS_CA), 0, 0);

        assertThat(verify(failed, isolation(failed)).reasonCode())
                .isEqualTo("KEY_OR_CA_ROTATION_NOT_CONVERGED");
    }

    @Test
    void rejectsInsufficientOverlapAndExpiredActiveGeneration() {
        var current = fixtures.rotations(fixtures.now.minusSeconds(3), null);
        var insufficient = replaceRotation(current, 0,
                copy(current.getFirst(), fixtures.now.minusSeconds(30), 599));
        var expired = replaceRotation(current, 1,
                copy(current.get(1), fixtures.now.minusSeconds(7_776_002), 600));

        var insufficientCertification = fixtures.certification(
                fixtures.observations(fixtures.now.minusSeconds(2), null),
                insufficient, 0, 0);
        var expiredCertification = fixtures.certification(
                fixtures.observations(fixtures.now.minusSeconds(2), null),
                expired, 0, 0);

        assertThat(verify(insufficientCertification,
                isolation(insufficientCertification)).reasonCode())
                .isEqualTo("KEY_OR_CA_ROTATION_NOT_CONVERGED");
        assertThat(verify(expiredCertification,
                isolation(expiredCertification)).reasonCode())
                .isEqualTo("ACTIVE_KEY_OR_CA_AGE_REJECTED");
    }

    @Test
    void rejectsExternalWriteAttemptEvenWhenEveryComponentIsReady() {
        var attempted = fixtures.certification(
                fixtures.observations(fixtures.now.minusSeconds(2), null),
                fixtures.rotations(fixtures.now.minusSeconds(3), null), 1, 0);
        var escaped = fixtures.certification(
                fixtures.observations(fixtures.now.minusSeconds(2), null),
                fixtures.rotations(fixtures.now.minusSeconds(3), null), 1, 1);

        assertThat(verify(attempted, isolation(attempted)).outcome())
                .isEqualTo(RegionalDataPlaneCertificationIntegrity.Outcome.WRITE_ESCAPE);
        assertThat(verify(escaped, isolation(escaped)).reasonCode())
                .isEqualTo("EXTERNAL_BUSINESS_WRITE_OBSERVED");
    }

    @Test
    void rejectsIsolationDecisionThatDoesNotBindExactCertification() {
        assertThat(verify(fixtures.certification, fixtures.isolationV1).reasonCode())
                .isEqualTo("ISOLATION_DECISION_CERTIFICATION_MISMATCH");
    }

    @Test
    void rejectsTamperedCertificationAndRevokedAuthorityKey() {
        var tampered = new RegionalDataPlaneCertification(
                fixtures.certification.schemaVersion(), fixtures.certification.certificationFingerprint(),
                fixtures.certification.certificationId(), fixtures.certification.revision(),
                fixtures.certification.contractRef(), fixtures.certification.scope(),
                fixtures.certification.region(), fixtures.certification.deployment(),
                fixtures.certification.observedAt(), fixtures.certification.validFrom(),
                fixtures.certification.expiresAt(), fixtures.certification.componentObservations(),
                fixtures.certification.rotationObservations(), 0, 0,
                fixtures.certification.issuer(), new ArrayList<>(fixtures.certification.proofRefs()),
                new com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal(
                        fixtures.certification.certificationSeal().schemaVersion(),
                        fixtures.certification.certificationSeal().materialFingerprint(),
                        fixtures.certification.certificationSeal().algorithm(),
                        fixtures.certification.certificationSeal().keyId(),
                        fixtures.certification.certificationSeal().signedAt(),
                        java.util.Base64.getEncoder().encodeToString(new byte[64])));
        var revoked = new RegionalDataPlaneCertificationIntegrity.AuthorityKey(
                fixtures.authorityKey.keyId(), fixtures.authorityKey.algorithm(),
                fixtures.authorityKey.encodedPublicKey(), fixtures.authorityKey.issuer(),
                fixtures.authorityKey.notBefore(), fixtures.authorityKey.notAfter(),
                RegionalDataPlaneCertificationIntegrity.KeyState.REVOKED);

        assertThat(verify(tampered, isolation(tampered)).outcome())
                .isEqualTo(RegionalDataPlaneCertificationIntegrity.Outcome.INVALID);
        assertThat(fixtures.integrity.verify(fixtures.contract, fixtures.certification, revoked,
                fixtures.isolationV2, fixtures.scope, fixtures.deployment,
                fixtures.now.plusSeconds(1), fixtures.now.plusSeconds(2)).reasonCode())
                .isEqualTo("AUTHORITY_POLICY_REJECTED");
    }

    private RegionalDataPlaneCertificationIntegrity.VerificationResult verify(
            RegionalDataPlaneCertification certification,
            MirrorDeploymentIsolationAttestationBundle isolation) {
        return fixtures.integrity.verify(fixtures.contract, certification, fixtures.authorityKey,
                isolation, fixtures.scope, fixtures.deployment,
                fixtures.now.plusSeconds(1), fixtures.now.plusSeconds(2));
    }

    private MirrorDeploymentIsolationAttestationBundle isolation(
            RegionalDataPlaneCertification certification) {
        return fixtures.isolationFixtures.bundleIntegrity.bundle(fixtures.isolationV1.scope(),
                fixtures.isolationV1.authorityKeySetRef(), fixtures.isolationV1.attestation(),
                fixtures.isolationV1.status(), certification.artifactRef());
    }

    private static java.util.List<RegionalDataPlaneCertification.RotationObservation>
    replaceRotation(
            java.util.List<RegionalDataPlaneCertification.RotationObservation> values,
            int index,
            RegionalDataPlaneCertification.RotationObservation replacement) {
        var updated = new ArrayList<>(values);
        updated.set(index, replacement);
        return java.util.List.copyOf(updated);
    }

    private static RegionalDataPlaneCertification.RotationObservation copy(
            RegionalDataPlaneCertification.RotationObservation source,
            java.time.Instant activatedAt,
            long overlapSeconds) {
        return new RegionalDataPlaneCertification.RotationObservation(
                source.kind(), source.previousGeneration(), source.activeGeneration(),
                activatedAt, overlapSeconds, source.previousGenerationRevoked(),
                source.allReplicasConverged(), source.staleSessionsDrained(),
                source.restartFree(), source.observedAt(), source.proofRefs());
    }
}
