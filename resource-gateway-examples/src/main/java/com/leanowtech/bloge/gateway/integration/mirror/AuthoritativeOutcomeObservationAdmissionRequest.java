package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Strict connector command for appending one authoritative outcome observation revision.
 *
 * @param schemaVersion exact admission command version
 * @param expectedPredecessorFingerprint blank for revision one, exact current head otherwise
 * @param observation unsigned authority closure or an exact previously returned signed revision
 */
public record AuthoritativeOutcomeObservationAdmissionRequest(
        String schemaVersion,
        String expectedPredecessorFingerprint,
        AuthoritativeOutcomeObservation observation
) {
    /** Exact first-generation protected admission version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeObservationAdmission.v1";

    /** Enforces a bounded canonical predecessor fence. */
    public AuthoritativeOutcomeObservationAdmissionRequest {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION
                : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported outcome observation admission schemaVersion");
        }
        expectedPredecessorFingerprint =
                expectedPredecessorFingerprint == null
                        ? ""
                        : expectedPredecessorFingerprint.trim();
        if (!expectedPredecessorFingerprint.isBlank()
                && !expectedPredecessorFingerprint.matches(
                "sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "expectedPredecessorFingerprint is invalid");
        }
        observation = Objects.requireNonNull(
                observation, "observation");
        if ((observation.revision() == 1)
                != expectedPredecessorFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "outcome observation predecessor does not match revision");
        }
    }
}
