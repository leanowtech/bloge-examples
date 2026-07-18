package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable typed request for one asynchronous suite-stability parent job.
 *
 * <p>The request owns the exact execution generation, immutable suite reference, idempotency key,
 * fixed attempt horizon, optional statistical policy, queue priority, and absolute deadline. Its
 * JSON projection is validated against the Schema packaged in the test-kit before any network
 * request can be made.</p>
 */
public final class TestSuiteStabilityJobRequest {

    /** Queue priority frozen at durable admission. */
    public enum Priority {
        /** Background or bulk validation. */
        LOW,
        /** Ordinary validation work. */
        NORMAL,
        /** Time-sensitive validation work. */
        HIGH
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CLIENT_REQUEST_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    private final String suiteId;
    private final long revision;
    private final String fingerprint;
    private final String clientRequestId;
    private final int attempts;
    private final TestSuiteStabilityStatisticalPolicy statisticalPolicy;
    private final Priority priority;
    private final Instant deadlineAt;
    private final ObjectNode wireRequest;
    private final String executionFingerprint;

    private TestSuiteStabilityJobRequest(
            String executionVersion,
            String suiteId,
            long revision,
            String fingerprint,
            String clientRequestId,
            int attempts,
            TestSuiteStabilityStatisticalPolicy statisticalPolicy,
            Map<String, ?> metadata,
            Priority priority,
            Instant deadlineAt) {
        this.suiteId = required(suiteId, "suiteId", 255, false);
        this.revision = revision;
        this.fingerprint = normalized(fingerprint);
        this.clientRequestId = required(clientRequestId, "clientRequestId", 255, true);
        this.attempts = attempts;
        this.statisticalPolicy = statisticalPolicy;
        this.priority = Objects.requireNonNull(priority, "priority");
        this.deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
        if (revision < 1 || !FINGERPRINT.matcher(this.fingerprint).matches()
                || deadlineAt.getNano() != 0) {
            throw new IllegalArgumentException(
                    "A positive revision, full fingerprint, and whole-second deadline are required");
        }

        ObjectNode execution = JSON.createObjectNode();
        execution.put("schemaVersion", executionVersion);
        ObjectNode suiteRef = execution.putObject("suiteRef");
        suiteRef.put("suiteId", this.suiteId);
        suiteRef.put("revision", revision);
        suiteRef.put("fingerprint", this.fingerprint);
        execution.put("clientRequestId", this.clientRequestId);
        execution.put("attempts", attempts);
        if (statisticalPolicy != null) {
            ObjectNode statistical = execution.putObject("statisticalPolicy");
            statistical.put("model", statisticalPolicy.model().name());
            statistical.put("claimScope", statisticalPolicy.claimScope().name());
            statistical.put("stoppingRule", statisticalPolicy.stoppingRule().name());
            statistical.put("censoringPolicy", statisticalPolicy.censoringPolicy().name());
            statistical.put("confidenceLevelBps", statisticalPolicy.confidenceLevelBps());
            statistical.put("maximumInstabilityRateBps",
                    statisticalPolicy.maximumInstabilityRateBps());
        }
        execution.set("metadata", metadata(metadata));
        TestingProtocolSchemaValidator.require(
                execution, "testSuiteStabilityExecutionRequest");

        wireRequest = JSON.createObjectNode();
        wireRequest.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_JOB_SUBMIT_REQUEST_V1);
        wireRequest.set("execution", execution);
        wireRequest.put("priority", priority.name());
        wireRequest.put("deadlineAt", deadlineAt.toString());
        TestingProtocolSchemaValidator.require(
                wireRequest, "testSuiteStabilityJobSubmitRequest");
        executionFingerprint = EvidenceVerificationSupport.sha256(execution);
    }

    /**
     * Creates a deterministic 3..20-attempt asynchronous stability request.
     *
     * @param suiteId exact immutable suite id
     * @param revision exact immutable suite revision
     * @param fingerprint full lowercase SHA-256 suite fingerprint
     * @param clientRequestId caller-stable submission idempotency identity
     * @param attempts precommitted attempt count from 3 through 20
     * @param metadata bounded scalar provenance metadata
     * @param priority immutable queue priority
     * @param deadlineAt whole-second absolute cooperative deadline
     * @return schema-validated immutable request
     */
    public static TestSuiteStabilityJobRequest fixedHorizon(
            String suiteId,
            long revision,
            String fingerprint,
            String clientRequestId,
            int attempts,
            Map<String, ?> metadata,
            Priority priority,
            Instant deadlineAt) {
        if (attempts < 3 || attempts > 20) {
            throw new IllegalArgumentException("Fixed stability attempts must be 3..20");
        }
        return new TestSuiteStabilityJobRequest(
                TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_REQUEST_V1,
                suiteId, revision, fingerprint, clientRequestId, attempts, null,
                metadata, priority, deadlineAt);
    }

    /**
     * Creates a precommitted fixed-horizon statistical asynchronous request.
     *
     * @param suiteId exact immutable suite id
     * @param revision exact immutable suite revision
     * @param fingerprint full lowercase SHA-256 suite fingerprint
     * @param clientRequestId caller-stable submission idempotency identity
     * @param attempts precommitted attempt count from 3 through 1000
     * @param statisticalPolicy exact supported probability policy
     * @param metadata bounded scalar provenance metadata
     * @param priority immutable queue priority
     * @param deadlineAt whole-second absolute cooperative deadline
     * @return schema-validated immutable request
     */
    public static TestSuiteStabilityJobRequest statistical(
            String suiteId,
            long revision,
            String fingerprint,
            String clientRequestId,
            int attempts,
            TestSuiteStabilityStatisticalPolicy statisticalPolicy,
            Map<String, ?> metadata,
            Priority priority,
            Instant deadlineAt) {
        TestSuiteStabilityStatisticalPolicy policy = Objects.requireNonNull(
                statisticalPolicy, "statisticalPolicy");
        if (attempts < TestSuiteStabilityStatisticalPolicy.MIN_ATTEMPTS
                || attempts > TestSuiteStabilityStatisticalPolicy.MAX_ATTEMPTS
                || !policy.horizonSufficient(attempts)) {
            throw new IllegalArgumentException(
                    "Statistical attempts must satisfy the bounded precommitted horizon");
        }
        return new TestSuiteStabilityJobRequest(
                TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_REQUEST_V2,
                suiteId, revision, fingerprint, clientRequestId, attempts, policy,
                metadata, priority, deadlineAt);
    }

    /**
     * Returns the exact suite id used in the route and execution body.
     *
     * @return exact suite id
     */
    public String suiteId() {
        return suiteId;
    }

    /**
     * Returns the exact immutable suite revision.
     *
     * @return positive suite revision
     */
    public long revision() {
        return revision;
    }

    /**
     * Returns the full suite fingerprint.
     *
     * @return lowercase SHA-256 suite fingerprint
     */
    public String fingerprint() {
        return fingerprint;
    }

    /**
     * Returns the caller-stable submission identity.
     *
     * @return submission idempotency key
     */
    public String clientRequestId() {
        return clientRequestId;
    }

    /**
     * Returns the precommitted attempt horizon.
     *
     * @return bounded attempt count
     */
    public int attempts() {
        return attempts;
    }

    /**
     * Returns the optional exact statistical policy.
     *
     * @return statistical policy, or {@code null} for deterministic fixed-horizon work
     */
    public TestSuiteStabilityStatisticalPolicy statisticalPolicy() {
        return statisticalPolicy;
    }

    /**
     * Returns the immutable queue priority.
     *
     * @return admitted priority intent
     */
    public Priority priority() {
        return priority;
    }

    /**
     * Returns the absolute cooperative deadline.
     *
     * @return whole-second deadline instant
     */
    public Instant deadlineAt() {
        return deadlineAt;
    }

    /**
     * Returns the canonical nested execution fingerprint.
     *
     * @return lowercase SHA-256 protocol fingerprint
     */
    public String executionFingerprint() {
        return executionFingerprint;
    }

    /**
     * Returns the complete schema-valid submission envelope.
     *
     * @return defensive JSON copy
     */
    public JsonNode rawRequest() {
        return wireRequest.deepCopy();
    }

    private static String required(
            String value,
            String field,
            int maximum,
            boolean protocolIdentifier) {
        String exact = normalized(value);
        if (exact.isBlank() || exact.length() > maximum
                || exact.contains("\r") || exact.contains("\n")
                || protocolIdentifier && !CLIENT_REQUEST_ID.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " is outside protocol bounds");
        }
        return exact;
    }

    private static JsonNode metadata(Map<String, ?> value) {
        if (value == null) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.valueToTree(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "Stability-job metadata cannot be encoded as bounded protocol JSON");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
