package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.List;
import java.util.Objects;

/**
 * Strict selection-authority command for one complete population revision.
 *
 * @param schemaVersion exact admission command version
 * @param expectedPredecessorFingerprint blank for revision one, exact current root otherwise
 * @param manifest unsigned or exactly signed selected-population root
 * @param chunks complete ordered content-addressed member chunks
 */
public record AuthoritativeOutcomeSelectedPopulationAdmissionRequest(
        String schemaVersion,
        String expectedPredecessorFingerprint,
        AuthoritativeOutcomeSelectedPopulationManifest
                manifest,
        List<AuthoritativeOutcomeSelectedPopulationChunk>
                chunks
) {
    /** Exact first-generation population admission version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeSelectedPopulationAdmission.v1";

    /** Enforces exact root revision and predecessor correspondence. */
    public AuthoritativeOutcomeSelectedPopulationAdmissionRequest {
        schemaVersion = version(schemaVersion);
        expectedPredecessorFingerprint =
                predecessor(
                        expectedPredecessorFingerprint);
        manifest = Objects.requireNonNull(
                manifest, "manifest");
        chunks = chunks == null
                ? List.of() : List.copyOf(chunks);
        if ((manifest.revision() == 1)
                != expectedPredecessorFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "selected-population predecessor does not match revision");
        }
    }

    private static String version(String value) {
        String exact = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw new IllegalArgumentException(
                    "unsupported selected-population admission schemaVersion");
        }
        return exact;
    }

    private static String predecessor(String value) {
        String exact = value == null
                ? "" : value.trim();
        if (!exact.isBlank()
                && !exact.matches(
                "sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "expectedPredecessorFingerprint is invalid");
        }
        return exact;
    }
}
