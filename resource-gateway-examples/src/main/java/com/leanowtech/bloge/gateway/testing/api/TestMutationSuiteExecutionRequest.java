package com.leanowtech.bloge.gateway.testing.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Versioned request for one exact immutable pure-DSL mutation suite.
 *
 * <p>The client request id shares the ordinary suite-run idempotency namespace. Scheduling policy
 * is deliberately local to each mutant: a proven assertion kill may stop that mutant's remaining
 * cases, but execution always proceeds to every other mutant so the score closure cannot be
 * selectively truncated.</p>
 *
 * @param schemaVersion exact mutation execution request generation
 * @param suiteRef exact immutable V5 suite reference
 * @param clientRequestId tenant- and environment-scoped idempotency key
 * @param strategy per-mutant case collection strategy
 * @param metadata bounded pipeline provenance copied only as a fingerprint into evidence
 */
public record TestMutationSuiteExecutionRequest(
        String schemaVersion,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        String clientRequestId,
        Strategy strategy,
        Map<String, Object> metadata
) {
    /** Current mutation-suite execution request protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testMutationSuiteExecutionRequest.v1";

    /** Per-mutant case scheduling policy. */
    public enum Strategy {
        /** Execute every oracle case for every mutant. */
        COLLECT_ALL,
        /** Stop only the current mutant's later cases after a signed assertion kill. */
        STOP_AFTER_KILL
    }

    /** Applies protocol defaults and freezes caller-owned metadata. */
    public TestMutationSuiteExecutionRequest {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        clientRequestId = normalized(clientRequestId);
        strategy = strategy == null ? Strategy.COLLECT_ALL : strategy;
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
