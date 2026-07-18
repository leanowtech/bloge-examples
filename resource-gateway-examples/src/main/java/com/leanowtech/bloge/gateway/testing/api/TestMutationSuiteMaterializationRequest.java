package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV5;

/**
 * Human-confirmed request to freeze one exact mutation plan and business oracle as a V5 suite.
 *
 * <p>The server regenerates the complete bounded plan. Callers provide only optimistic content
 * identities and cannot upload executable DSL, individual mutant source, or a trimmed mutant set.</p>
 *
 * @param schemaVersion exact request protocol version
 * @param suiteId stable destination mutation-suite id
 * @param classification destination governance classification
 * @param expectedTargetFingerprint exact reviewed baseline target
 * @param expectedSourceFingerprint exact reviewed recoverable source
 * @param expectedGraphArtifactFingerprint exact reviewed baseline graph artifact
 * @param expectedPlanFingerprint exact reviewed mutation plan
 * @param maxMutants exact deterministic generation bound from 1 through 16
 * @param oracleSuiteRef exact existing governed executable suite
 * @param acceptPlanningGaps explicit acknowledgement required for a partial plan
 * @param scorePolicy score and inconclusive policy frozen before execution
 */
public record TestMutationSuiteMaterializationRequest(
        String schemaVersion,
        String suiteId,
        String classification,
        String expectedTargetFingerprint,
        String expectedSourceFingerprint,
        String expectedGraphArtifactFingerprint,
        String expectedPlanFingerprint,
        int maxMutants,
        TestSuiteExecutionRequest.SuiteRef oracleSuiteRef,
        boolean acceptPlanningGaps,
        TestSuiteV5.MutationScorePolicy scorePolicy
) {
    /** Current mutation-suite materialization request version. */
    public static final String SCHEMA_VERSION = "bloge.testMutationSuiteMaterializationRequest.v1";

    /** Normalizes scalar values while retaining every exact proof coordinate. */
    public TestMutationSuiteMaterializationRequest {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        suiteId = normalized(suiteId);
        classification = defaulted(classification, "INTERNAL").toUpperCase(java.util.Locale.ROOT);
        expectedTargetFingerprint = normalized(expectedTargetFingerprint);
        expectedSourceFingerprint = normalized(expectedSourceFingerprint);
        expectedGraphArtifactFingerprint = normalized(expectedGraphArtifactFingerprint);
        expectedPlanFingerprint = normalized(expectedPlanFingerprint);
    }

    private static String defaulted(String value, String fallback) {
        String safe = normalized(value);
        return safe.isBlank() ? fallback : safe;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
