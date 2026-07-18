package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Map;
import java.util.Objects;

/** Shared deterministic identity derivation for synchronous and queued stability execution. */
public final class TestSuiteStabilityExecutionIdentity {

    private TestSuiteStabilityExecutionIdentity() {
    }

    /**
     * Derives a payload-free descriptor from a verified request identity.
     *
     * @param objectMapper canonical protocol mapper
     * @param identity verified execution scope
     * @param clientRequestId caller-stable idempotency identity
     * @param requestFingerprint canonical stability request fingerprint
     * @param classification frozen suite classification
     * @return exact deterministic parent descriptor
     */
    public static TestSuiteStabilityExecutionDescriptor descriptor(
            ObjectMapper objectMapper,
            IntegrationRequestContext identity,
            String clientRequestId,
            String requestFingerprint,
            String classification) {
        IntegrationRequestContext scope = Objects.requireNonNull(identity, "identity");
        return descriptor(objectMapper, scope.tenantId(), scope.environmentId(),
                clientRequestId, requestFingerprint, classification);
    }

    /**
     * Derives a payload-free descriptor from a durable queue job.
     *
     * @param objectMapper canonical protocol mapper
     * @param job integrity-verified durable job
     * @return exact deterministic parent descriptor
     */
    public static TestSuiteStabilityExecutionDescriptor descriptor(
            ObjectMapper objectMapper,
            TestSuiteStabilityJobRecord job) {
        TestSuiteStabilityJobRecord source = Objects.requireNonNull(job, "job");
        return descriptor(objectMapper, source.tenantId(), source.environmentId(),
                source.request().clientRequestId(), source.requestFingerprint(),
                source.classification());
    }

    private static TestSuiteStabilityExecutionDescriptor descriptor(
            ObjectMapper objectMapper,
            String tenantId,
            String environmentId,
            String clientRequestId,
            String requestFingerprint,
            String classification) {
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper");
        String fingerprint = ProtocolFingerprint.of(mapper, Map.of(
                "schemaVersion", "bloge.testSuiteStabilityRunIdentity.v1",
                "tenantId", tenantId,
                "environmentId", environmentId,
                "requestFingerprint", requestFingerprint));
        return new TestSuiteStabilityExecutionDescriptor(
                "stability-" + fingerprint.substring("sha256:".length()),
                tenantId, environmentId, clientRequestId, requestFingerprint, classification);
    }
}
