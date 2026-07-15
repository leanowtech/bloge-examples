package com.leanowtech.bloge.gateway.testing.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Versioned request to execute one exact immutable test-suite revision.
 *
 * <p>{@link #clientRequestId()} is a tenant- and environment-scoped idempotency key. Reusing it
 * with byte-equivalent normalized intent returns the existing suite run; reusing it with different
 * intent is rejected before another case can execute.</p>
 *
 * @param schemaVersion suite-execution request protocol version
 * @param suiteRef exact immutable suite registry reference
 * @param clientRequestId caller-stable idempotency key
 * @param strategy whether to collect every case or stop scheduling after the first failure
 * @param metadata bounded pipeline and invocation provenance retained on aggregate evidence
 */
public record TestSuiteExecutionRequest(
        String schemaVersion,
        SuiteRef suiteRef,
        String clientRequestId,
        Strategy strategy,
        Map<String, Object> metadata
) {
    /** Current suite-execution request protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteExecutionRequest.v1";

    /**
     * Scheduling policy. FAIL_FAST never interrupts an already running case; it only prevents new
     * cases from being scheduled after a terminal non-pass result.
     */
    public enum Strategy {
        COLLECT_ALL,
        FAIL_FAST
    }

    /**
     * Exact content-addressed suite dependency.
     *
     * @param suiteId stable suite registry identifier
     * @param revision exact positive immutable revision
     * @param fingerprint full suite content fingerprint
     */
    public record SuiteRef(String suiteId, long revision, String fingerprint) {
        /** Normalizes identifiers without permitting an implicit latest lookup. */
        public SuiteRef {
            suiteId = normalized(suiteId);
            fingerprint = normalized(fingerprint).toLowerCase(Locale.ROOT);
        }
    }

    /** Applies protocol defaults and defensively freezes caller metadata. */
    public TestSuiteExecutionRequest {
        schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION : normalized(schemaVersion);
        clientRequestId = normalized(clientRequestId);
        strategy = strategy == null ? Strategy.COLLECT_ALL : strategy;
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
