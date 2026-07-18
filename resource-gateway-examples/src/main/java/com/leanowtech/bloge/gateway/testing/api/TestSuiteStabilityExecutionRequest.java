package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Versioned request for a bounded, idempotent stability rerun of one exact suite revision.
 *
 * <p>The service always executes each attempt with {@link TestSuiteExecutionRequest.Strategy#COLLECT_ALL}
 * so every case has an equivalent observation coordinate. Attempt-specific idempotency keys are
 * derived from {@link #clientRequestId()}, allowing a retry after process loss to reuse completed
 * durable source runs.</p>
 *
 * @param schemaVersion exact request protocol version
 * @param suiteRef exact immutable suite revision
 * @param clientRequestId caller-stable parent idempotency key
 * @param attempts bounded independent rerun count
 * @param metadata bounded pipeline and invocation provenance
 */
public record TestSuiteStabilityExecutionRequest(
        String schemaVersion,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        String clientRequestId,
        int attempts,
        Map<String, Object> metadata
) {
    /** Current stability-execution request protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteStabilityExecutionRequest.v1";

    /** Applies the minimum useful attempt count and defensively freezes metadata. */
    public TestSuiteStabilityExecutionRequest {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        clientRequestId = normalized(clientRequestId);
        attempts = attempts == 0 ? TestSuiteStabilityEvidence.MIN_ATTEMPTS : attempts;
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
