package com.leanowtech.bloge.gateway.testing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Versioned, immutable bundle of execution-control rules and assertions for one frozen target.
 *
 * @param schemaVersion fixture-bundle schema version
 * @param fixtureBundleId stable bundle id
 * @param revision immutable registry revision
 * @param targetFingerprint required target artifact fingerprint
 * @param classification payload-governance classification
 * @param logicalClock optional run-scoped logical clock used by deterministic time controls
 * @param randomSeed optional deterministic seed shared by random and UUID execution services
 * @param rules invocation control rules
 * @param assertions post-run assertions
 * @param metadata bounded ownership/provenance metadata and the reserved execution-service controls
 */
public record FixtureBundle(
        String schemaVersion,
        String fixtureBundleId,
        long revision,
        String targetFingerprint,
        String classification,
        Instant logicalClock,
        Long randomSeed,
        List<FixtureRule> rules,
        List<Assertion> assertions,
        Map<String, Object> metadata
) {
    /** Current fixture-bundle protocol version. */
    public static final String SCHEMA_VERSION = "bloge.fixtureBundle.v1";

    /** Creates immutable bundle collections and normalizes protocol defaults. */
    public FixtureBundle {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        fixtureBundleId = trimmed(fixtureBundleId);
        targetFingerprint = trimmed(targetFingerprint);
        classification = defaulted(classification, "INTERNAL");
        rules = rules == null ? List.of() : List.copyOf(rules);
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
        metadata = ProtocolJsonValue.freezeMap(metadata);
    }

    /**
     * Generic assertion contract shared by operator, subgraph, and graph test targets.
     *
     * @param scope assertion scope such as OUTPUT_PATH or NODE_STATUS
     * @param nodeId optional node scope
     * @param path optional JSON Pointer
     * @param operator comparison operator
     * @param expected expected value
     * @param numericTolerance optional absolute numeric tolerance
     */
    public record Assertion(
            String scope,
            String nodeId,
            String path,
            String operator,
            Object expected,
            Double numericTolerance
    ) {
        /** Normalizes assertion identifiers and JSON Pointer text. */
        public Assertion {
            scope = trimmed(scope);
            nodeId = trimmed(nodeId);
            path = trimmed(path);
            operator = trimmed(operator);
            expected = ProtocolJsonValue.freeze(expected);
        }
    }

    private static String defaulted(String value, String fallback) {
        String normalized = trimmed(value);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
