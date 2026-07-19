package com.leanowtech.bloge.gateway.testing.domain;

import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;

import java.util.regex.Pattern;

/**
 * Signed payload-free projection retained independently from a full stability run.
 *
 * <p>The projection is deliberately limited to material needed by longitudinal analysis. It
 * carries source evidence and attestation identities so a consumer can cross-check the full source
 * while it remains available, while the observation signature survives the shorter source
 * retention lifecycle.</p>
 *
 * @param schemaVersion exact observation protocol generation
 * @param observationId deterministic scope-and-source observation identity
 * @param scopeFingerprint payload-free tenant, environment, and exact-suite identity
 * @param suiteRef exact immutable suite revision
 * @param sourceRequestFingerprint canonical source stability request identity
 * @param source deterministic payload-free source projection
 */
public record TestSuiteStabilityObservationEvidence(
        String schemaVersion,
        String observationId,
        String scopeFingerprint,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        String sourceRequestFingerprint,
        TestSuiteStabilityTrendEvidence.RunObservation source
) {
    /** Current compact observation evidence generation. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteStabilityObservationEvidence.v1";
    private static final Pattern OBSERVATION_ID =
            Pattern.compile("stability-observation-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates an exact payload-free source projection. */
    public TestSuiteStabilityObservationEvidence {
        schemaVersion = normalized(schemaVersion);
        observationId = normalized(observationId);
        scopeFingerprint = normalized(scopeFingerprint);
        sourceRequestFingerprint = normalized(sourceRequestFingerprint);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !OBSERVATION_ID.matcher(observationId).matches()
                || !FINGERPRINT.matcher(scopeFingerprint).matches()
                || suiteRef == null || normalized(suiteRef.suiteId()).isBlank()
                || suiteRef.revision() < 1
                || !FINGERPRINT.matcher(normalized(suiteRef.fingerprint())).matches()
                || !FINGERPRINT.matcher(sourceRequestFingerprint).matches()
                || source == null) {
            throw new IllegalArgumentException(
                    "Complete suite-stability observation evidence is required");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
