package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityStatisticalPolicy;

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
 * @param statisticalPolicy precommitted statistical model; required by request v2/v3
 * @param metadata bounded pipeline and invocation provenance
 */
public record TestSuiteStabilityExecutionRequest(
        String schemaVersion,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        String clientRequestId,
        int attempts,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        TestSuiteStabilityStatisticalPolicy statisticalPolicy,
        Map<String, Object> metadata
) {
    /** Deterministic bounded-rerun request without a probability model. */
    public static final String SCHEMA_VERSION_V1 = "bloge.testSuiteStabilityExecutionRequest.v1";
    /** Legacy request version with the zero-event statistical policy. */
    public static final String SCHEMA_VERSION_V2 = "bloge.testSuiteStabilityExecutionRequest.v2";
    /** Current request version with the baseline-conditional exact-rate policy. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteStabilityExecutionRequest.v3";

    /** Applies the minimum useful attempt count and defensively freezes metadata. */
    public TestSuiteStabilityExecutionRequest {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? defaultVersion(statisticalPolicy) : normalized(schemaVersion);
        clientRequestId = normalized(clientRequestId);
        attempts = attempts == 0 ? TestSuiteStabilityEvidence.MIN_ATTEMPTS : attempts;
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /**
     * Backward-compatible deterministic request constructor.
     *
     * @param schemaVersion request generation, blank for v1
     * @param suiteRef exact immutable suite revision
     * @param clientRequestId caller-stable idempotency key
     * @param attempts exact deterministic rerun count
     * @param metadata bounded caller provenance
     */
    public TestSuiteStabilityExecutionRequest(
            String schemaVersion,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            String clientRequestId,
            int attempts,
            Map<String, Object> metadata) {
        this(schemaVersion, suiteRef, clientRequestId, attempts, null, metadata);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String defaultVersion(TestSuiteStabilityStatisticalPolicy policy) {
        if (policy == null) {
            return SCHEMA_VERSION_V1;
        }
        return policy.model()
                == TestSuiteStabilityStatisticalPolicy.Model.ZERO_INSTABILITY_EXACT_BINOMIAL
                ? SCHEMA_VERSION_V2 : SCHEMA_VERSION;
    }
}
