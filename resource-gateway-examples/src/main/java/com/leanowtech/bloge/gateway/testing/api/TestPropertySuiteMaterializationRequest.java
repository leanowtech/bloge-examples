package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuite;

/**
 * Human-confirmed request to materialize one exact seeded property plan as an immutable suite.
 *
 * <p>The request deliberately contains the complete generation coordinates and three optimistic
 * fingerprints. The server regenerates the plan rather than trusting caller-supplied cases. One
 * exact existing fixture is required because generated inputs without business assertions are not
 * an executable correctness asset.</p>
 *
 * @param schemaVersion request protocol version
 * @param suiteId stable destination suite identifier
 * @param classification governed destination classification
 * @param expectedTargetFingerprint exact target reviewed by the caller
 * @param expectedInputSchemaFingerprint exact projected input schema reviewed by the caller
 * @param expectedPlanFingerprint exact generated property plan reviewed by the caller
 * @param seed deterministic generation seed
 * @param trials requested unique root trials
 * @param maxShrinkSteps maximum precomputed shrink candidates per root
 * @param fixtureRef exact existing governed fixture with business assertions
 * @param acceptGenerationGaps explicit acknowledgement required for a partial plan
 */
public record TestPropertySuiteMaterializationRequest(
        String schemaVersion,
        String suiteId,
        String classification,
        String expectedTargetFingerprint,
        String expectedInputSchemaFingerprint,
        String expectedPlanFingerprint,
        long seed,
        int trials,
        int maxShrinkSteps,
        TestSuite.FixtureBundleRef fixtureRef,
        boolean acceptGenerationGaps
) {
    /** Current property-suite materialization request version. */
    public static final String SCHEMA_VERSION = "bloge.testPropertySuiteMaterializationRequest.v1";

    /** Normalizes scalar values while retaining exact generation coordinates. */
    public TestPropertySuiteMaterializationRequest {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        suiteId = normalized(suiteId);
        classification = defaulted(classification, "INTERNAL").toUpperCase(java.util.Locale.ROOT);
        expectedTargetFingerprint = normalized(expectedTargetFingerprint);
        expectedInputSchemaFingerprint = normalized(expectedInputSchemaFingerprint);
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
