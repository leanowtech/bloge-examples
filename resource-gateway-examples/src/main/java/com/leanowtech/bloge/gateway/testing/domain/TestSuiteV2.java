package com.leanowtech.bloge.gateway.testing.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable test-suite generation with first-class orchestration-semantic coverage policy.
 *
 * <p>This is a separate canonical record rather than an optional field on v1. Historical v1 suite
 * fingerprints therefore remain byte-for-byte verifiable while v2 promotion can require typed
 * semantic facts.</p>
 *
 * @param schemaVersion exact v2 suite schema version
 * @param suiteId stable suite identifier
 * @param revision immutable suite revision
 * @param target exact graph or operator artifact under test
 * @param classification maximum data classification
 * @param cases ordered deterministic test cases
 * @param coveragePolicy structural coverage requirements
 * @param semanticCoveragePolicy semantic orchestration requirements
 * @param promotionPolicy promotion eligibility policy
 * @param metadata bounded ownership and provenance facts
 */
public record TestSuiteV2(
        String schemaVersion,
        String suiteId,
        long revision,
        TestSuite.Target target,
        String classification,
        List<TestSuite.TestCase> cases,
        TestSuite.CoveragePolicy coveragePolicy,
        SemanticCoveragePolicy semanticCoveragePolicy,
        TestSuite.PromotionPolicy promotionPolicy,
        Map<String, Object> metadata
) implements TestSuiteProtocol {
    /** Current semantic test-suite protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testSuite.v2";

    /** Normalizes common values while preserving the independent v2 canonical shape. */
    public TestSuiteV2 {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        suiteId = normalized(suiteId);
        classification = defaulted(classification, "INTERNAL").toUpperCase(Locale.ROOT);
        cases = cases == null ? List.of() : List.copyOf(cases);
        coveragePolicy = coveragePolicy == null ? TestSuite.CoveragePolicy.defaults() : coveragePolicy;
        semanticCoveragePolicy = semanticCoveragePolicy == null
                ? SemanticCoveragePolicy.empty() : semanticCoveragePolicy;
        promotionPolicy = promotionPolicy == null
                ? TestSuite.PromotionPolicy.defaults() : promotionPolicy;
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    private static String defaulted(String value, String fallback) {
        String safe = normalized(value);
        return safe.isBlank() ? fallback : safe;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
