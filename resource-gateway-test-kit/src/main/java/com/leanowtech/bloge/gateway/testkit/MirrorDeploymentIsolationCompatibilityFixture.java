package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

/**
 * Fixed signed deployment-isolation fixture shared by every independent client implementation.
 *
 * @param attestation detached strict attestation JSON
 * @param verificationKey externally pinned authority key
 * @param expectedDeployment immutable local deployment coordinates
 * @param executionStartedAt fixture execution start
 * @param executionCompletedAt fixture execution completion
 */
public record MirrorDeploymentIsolationCompatibilityFixture(
        JsonNode attestation,
        MirrorDeploymentIsolationVerificationKey verificationKey,
        MirrorDeploymentIdentity expectedDeployment,
        Instant executionStartedAt,
        Instant executionCompletedAt
) {
    /** Validates fixture completeness and detaches mutable JSON. */
    public MirrorDeploymentIsolationCompatibilityFixture {
        if (attestation == null || !attestation.isObject()) {
            throw new IllegalArgumentException(
                    "deployment isolation compatibility fixture is invalid");
        }
        attestation = attestation.deepCopy();
        verificationKey = Objects.requireNonNull(verificationKey, "verificationKey");
        expectedDeployment = Objects.requireNonNull(expectedDeployment, "expectedDeployment");
        executionStartedAt = Objects.requireNonNull(executionStartedAt, "executionStartedAt");
        executionCompletedAt = Objects.requireNonNull(executionCompletedAt,
                "executionCompletedAt");
        if (executionCompletedAt.isBefore(executionStartedAt)) {
            throw new IllegalArgumentException("fixture execution window is invalid");
        }
    }

    /**
     * Creates a detached fixture whose JSON value is safe for caller mutation.
     *
     * @return independent copy safe for caller mutation
     */
    public MirrorDeploymentIsolationCompatibilityFixture detachedCopy() {
        return new MirrorDeploymentIsolationCompatibilityFixture(attestation.deepCopy(),
                verificationKey, expectedDeployment, executionStartedAt, executionCompletedAt);
    }
}
