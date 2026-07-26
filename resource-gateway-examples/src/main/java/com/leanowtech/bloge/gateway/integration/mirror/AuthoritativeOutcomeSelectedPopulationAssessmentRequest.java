package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.regex.Pattern;

/**
 * Strict governance command for one exact current-head completeness projection.
 *
 * @param schemaVersion exact command version
 * @param populationRevision exact immutable selected-population revision
 * @param assessmentId stable assessment identity
 * @param assessmentRevision positive immutable assessment revision
 * @param expectedPredecessorFingerprint blank for revision one, exact assessment head otherwise
 */
public record AuthoritativeOutcomeSelectedPopulationAssessmentRequest(
        String schemaVersion,
        long populationRevision,
        String assessmentId,
        long assessmentRevision,
        String expectedPredecessorFingerprint
) {
    /** Current completeness assessment command version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeSelectedPopulationAssessmentRequest.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");

    /** Enforces exact assessment and predecessor coordinates. */
    public AuthoritativeOutcomeSelectedPopulationAssessmentRequest {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported selected-population assessment request schemaVersion");
        }
        assessmentId = assessmentId == null
                ? "" : assessmentId.trim();
        expectedPredecessorFingerprint =
                expectedPredecessorFingerprint == null
                        ? ""
                        : expectedPredecessorFingerprint.trim();
        if (populationRevision < 1
                || assessmentRevision < 1
                || !IDENTIFIER.matcher(
                assessmentId).matches()
                || !expectedPredecessorFingerprint
                .isBlank()
                && !expectedPredecessorFingerprint
                .matches("sha256:[a-f0-9]{64}")
                || (assessmentRevision == 1)
                != expectedPredecessorFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "selected-population assessment request coordinates are invalid");
        }
    }
}
