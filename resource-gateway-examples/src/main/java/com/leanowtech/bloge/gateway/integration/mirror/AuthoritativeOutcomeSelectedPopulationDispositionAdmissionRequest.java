package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Strict deletion-authority command for one legal-disposition revision.
 *
 * @param schemaVersion exact command version
 * @param expectedPredecessorFingerprint blank for revision one, exact current disposition otherwise
 * @param disposition unsigned or exactly signed legal disposition
 */
public record
AuthoritativeOutcomeSelectedPopulationDispositionAdmissionRequest(
        String schemaVersion,
        String expectedPredecessorFingerprint,
        AuthoritativeOutcomeSelectedPopulationDisposition
                disposition
) {
    /** Current legal-disposition admission command version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeSelectedPopulationDispositionAdmission.v1";

    /** Enforces revision and predecessor correspondence. */
    public AuthoritativeOutcomeSelectedPopulationDispositionAdmissionRequest {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported selected-member disposition admission schemaVersion");
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
        disposition = Objects.requireNonNull(
                disposition, "disposition");
        if ((disposition.revision() == 1)
                != expectedPredecessorFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "selected-member disposition predecessor does not match revision");
        }
    }
}
