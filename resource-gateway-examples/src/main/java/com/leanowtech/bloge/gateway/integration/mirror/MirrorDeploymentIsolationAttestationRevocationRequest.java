package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Optimistically fenced command that irreversibly revokes one current isolation attestation.
 *
 * @param schemaVersion revocation-command protocol version
 * @param attestationRevision expected current external revision
 * @param attestationFingerprint expected current external content address
 * @param expectedStatusRevision expected current local status revision
 * @param expectedStatusFingerprint expected current local status content address
 * @param reason closed payload-free revocation reason
 */
public record MirrorDeploymentIsolationAttestationRevocationRequest(
        String schemaVersion,
        long attestationRevision,
        String attestationFingerprint,
        long expectedStatusRevision,
        String expectedStatusFingerprint,
        MirrorDeploymentIsolationAttestationStatusPublication.Reason reason
) {
    /** Current irreversible revocation-command protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorDeploymentIsolationAttestationRevocationRequest.v1";

    /** Validates the exact optimistic fence and forbids the initial acceptance reason. */
    public MirrorDeploymentIsolationAttestationRevocationRequest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported deployment isolation attestation revocation schemaVersion");
        }
        reason = Objects.requireNonNull(reason, "reason");
        if (reason == MirrorDeploymentIsolationAttestationStatusPublication.Reason.ACCEPTED) {
            throw new IllegalArgumentException("ACCEPTED is not a revocation reason");
        }
        new MirrorDeploymentIsolationAttestationRepository.CurrentExpectation(
                attestationRevision, attestationFingerprint,
                expectedStatusRevision, expectedStatusFingerprint);
    }

    /**
     * Returns the exact optimistic repository fence.
     *
     * @return validated current expectation
     */
    public MirrorDeploymentIsolationAttestationRepository.CurrentExpectation expectation() {
        return new MirrorDeploymentIsolationAttestationRepository.CurrentExpectation(
                attestationRevision, attestationFingerprint,
                expectedStatusRevision, expectedStatusFingerprint);
    }
}
