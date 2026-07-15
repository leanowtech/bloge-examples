package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * CI-oriented, payload-safe projection of one persisted test execution response.
 * The raw response remains available for explicit application-level inspection, while built-in
 * assertions and reporters only consume the bounded metadata fields in this record.
 *
 * @param runId persisted run id
 * @param status normalized terminal status
 * @param evidenceClass exploratory or certifiable evidence class
 * @param targetFingerprint frozen graph/dependency fingerprint
 * @param fixtureBundleFingerprint resolved fixture fingerprint
 * @param planFingerprint compiled control-plan fingerprint
 * @param nodeTraces payload-free node summaries
 * @param edgeTraces payload-free edge transfer summaries
 * @param fixtureConsumptions fixture-use summaries
 * @param assertionResults payload-free assertion summaries
 * @param diagnostics bounded server diagnostics, excluded from built-in reports
 * @param integrity detached signature and projection facts
 * @param rawResponse defensive complete response for explicit authorized inspection
 */
public record TestRun(
        String runId,
        Status status,
        EvidenceClass evidenceClass,
        String targetFingerprint,
        String fixtureBundleFingerprint,
        String planFingerprint,
        List<NodeTrace> nodeTraces,
        List<EdgeTrace> edgeTraces,
        List<FixtureConsumption> fixtureConsumptions,
        List<AssertionResult> assertionResults,
        List<String> diagnostics,
        Integrity integrity,
        JsonNode rawResponse
) {
    /** The ten terminal states frozen by testing-control-plane v1. */
    public enum Status {
        /** Graph execution and every assertion completed successfully. */
        PASSED,
        /** Execution completed but at least one declared assertion failed. */
        ASSERTION_FAILED,
        /** Graph execution failed before all assertions could pass. */
        EXECUTION_FAILED,
        /** The supplied control plan was invalid and was not executed. */
        CONTROL_PLAN_REJECTED,
        /** A runtime interaction had no eligible fixture rule. */
        FIXTURE_UNMATCHED,
        /** At least one required fixture rule was never consumed. */
        FIXTURE_UNUSED,
        /** The frozen control plan could not be resolved for execution. */
        CONTROL_PLAN_UNAVAILABLE,
        /** Execution finished without the evidence required by the requested trust level. */
        EVIDENCE_INCOMPLETE,
        /** Execution was explicitly cancelled before completion. */
        CANCELLED,
        /** Execution exceeded its configured deadline. */
        TIMED_OUT
    }

    /** Evidence trust class consumed by release gates. */
    public enum EvidenceClass {
        /** Useful for development feedback but not eligible for release certification. */
        EXPLORATORY,
        /** Meets the frozen target, fixture, isolation, and evidence requirements. */
        CERTIFIABLE
    }

    /** Detached signature state returned by testing-control-plane v2. */
    public enum SignatureStatus {
        /** Signature was verified by the producer before persistence and response projection. */
        VERIFIED,
        /** Historical v1 evidence has no detached signature. */
        UNSIGNED,
        /** The producer could not establish integrity with its signing authority. */
        VERIFICATION_UNAVAILABLE
    }

    /** Evidence projection carried by the execution response. */
    public enum Projection {
        /** Terminal and bounded aggregate facts only. */
        SUMMARY,
        /** Payload-free node and edge facts in addition to summary data. */
        STANDARD,
        /** Complete sanitized evidence covered by the detached signature. */
        FULL
    }

    /**
     * Payload-free cryptographic integrity projection.
     *
     * @param evidenceFingerprint canonical fingerprint of complete persisted evidence
     * @param signatureStatus detached signature state
     * @param keyId verification-key identifier
     * @param algorithm signature algorithm
     * @param signedAt signing time included in the producer's signed canonical envelope
     * @param signature base64 detached signature
     * @param projection response evidence projection
     * @param projectionFingerprint canonical fingerprint of response evidence
     * @param independentlyVerifiable whether this response contains exact complete signed evidence
     */
    public record Integrity(String evidenceFingerprint, SignatureStatus signatureStatus,
                            String keyId, String algorithm, Instant signedAt, String signature,
                            Projection projection, String projectionFingerprint,
                            boolean independentlyVerifiable) {
        /** Normalizes bounded protocol labels. */
        public Integrity {
            evidenceFingerprint = normalized(evidenceFingerprint);
            signatureStatus = signatureStatus == null ? SignatureStatus.UNSIGNED : signatureStatus;
            keyId = normalized(keyId);
            algorithm = normalized(algorithm);
            signedAt = signedAt == null ? Instant.EPOCH : signedAt;
            signature = normalized(signature);
            projection = projection == null ? Projection.FULL : projection;
            projectionFingerprint = normalized(projectionFingerprint);
        }

        /**
         * Creates the compatibility state for a v1 response without an integrity manifest.
         *
         * @return unsigned integrity projection
         */
        public static Integrity legacyUnsigned() {
            return new Integrity("", SignatureStatus.UNSIGNED, "", "", Instant.EPOCH, "",
                    Projection.FULL, "", false);
        }

        /**
         * Indicates whether the producer established a detached signature.
         *
         * @return true only for a verified non-empty signature manifest
         */
        public boolean signed() {
            return signatureStatus == SignatureStatus.VERIFIED
                    && !evidenceFingerprint.isBlank() && !keyId.isBlank() && !signature.isBlank();
        }
    }

    /**
     * Payload-free node execution summary.
     *
     * @param nodeId graph node id
     * @param operatorRef resolved operator reference
     * @param status execution outcome such as SUCCESS, MOCKED, FAILED, or SKIPPED
     * @param fidelity observed fixture fidelity
     * @param errorCode normalized error code
     * @param durationMs observed duration in milliseconds
     * @param invocationSiteId stable structural invocation-site id
     * @param graphPath stable path of the graph that owns the occurrence
     * @param correlationKey foreach, loop, or business correlation coordinate
     * @param occurrence one-based site binding occurrence; zero means legacy unknown
     * @param graphOccurrence one-based containing-graph occurrence; zero means legacy unknown
     * @param attempts ordered payload-free delegate-attempt summaries
     */
    public record NodeTrace(String nodeId, String operatorRef, String status, String fidelity,
                            String errorCode, long durationMs, String invocationSiteId,
                            String graphPath, String correlationKey, int occurrence,
                            int graphOccurrence, List<AttemptTrace> attempts) {
        /**
         * Creates a backward-compatible summary without structural occurrence coordinates.
         *
         * @param nodeId graph node id
         * @param operatorRef resolved operator reference
         * @param status execution outcome
         * @param fidelity observed fixture fidelity
         * @param errorCode normalized error code
         * @param durationMs observed duration in milliseconds
         */
        public NodeTrace(String nodeId, String operatorRef, String status, String fidelity,
                         String errorCode, long durationMs) {
            this(nodeId, operatorRef, status, fidelity, errorCode, durationMs,
                    "", "", "", 0, 0, List.of());
        }

        /** Normalizes the nested attempt collection and rejects invalid wire coordinates. */
        public NodeTrace {
            if (durationMs < 0 || occurrence < 0 || graphOccurrence < 0) {
                throw new IllegalArgumentException("Node trace durations and occurrences must be non-negative");
            }
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
        }
    }

    /**
     * One payload-free delegate call within a node occurrence.
     *
     * @param attempt one-based retry attempt; zero means legacy unknown
     * @param status SUCCESS, FAILED, TIMEOUT, or MOCKED
     * @param fidelity observed execution fidelity
     * @param errorCode normalized failure code
     * @param durationMs observed attempt duration in milliseconds
     */
    public record AttemptTrace(int attempt, String status, String fidelity,
                               String errorCode, long durationMs) {
        /** Rejects invalid wire counters before they reach CI assertions. */
        public AttemptTrace {
            if (attempt < 0 || durationMs < 0) {
                throw new IllegalArgumentException("Attempt trace counters must be non-negative");
            }
        }
    }

    /**
     * Payload-free edge transfer summary addressable within one containing-graph occurrence.
     *
     * @param edgeId stable edge id
     * @param status TRANSFERRED, SKIPPED, or NOT_TRANSFERRED
     * @param graphPath stable path of the graph that owns the edge
     * @param correlationKey foreach, loop, or business correlation coordinate
     * @param graphOccurrence one-based containing-graph occurrence; zero means legacy unknown
     * @param fromInvocationSiteId stable source invocation-site id
     * @param toInvocationSiteId stable target invocation-site id
     */
    public record EdgeTrace(String edgeId, String status, String graphPath,
                            String correlationKey, int graphOccurrence,
                            String fromInvocationSiteId, String toInvocationSiteId) {
        /** Rejects invalid wire coordinates before they reach CI assertions. */
        public EdgeTrace {
            if (graphOccurrence < 0) {
                throw new IllegalArgumentException("Edge graphOccurrence must be non-negative");
            }
        }
    }

    /**
     * Rule-consumption summary used to prove required fixtures were exercised.
     *
     * @param ruleId fixture rule id
     * @param uses observed use count
     * @param required whether consumption was mandatory
     * @param status normalized consumption status
     */
    public record FixtureConsumption(String ruleId, int uses, boolean required, String status) {
        /**
         * Indicates whether the server accepted this fixture-consumption fact.
         *
         * @return true when this consumption fact satisfies its declared requirement
         */
        public boolean satisfied() {
            return "SATISFIED".equals(status);
        }
    }

    /**
     * Payload-free assertion summary.
     *
     * @param scope assertion scope
     * @param path asserted JSON Pointer
     * @param passed assertion outcome
     * @param diagnostic bounded diagnostic, excluded from built-in reports
     */
    public record AssertionResult(String scope, String path, boolean passed, String diagnostic) {
    }

    /** Normalizes collections and protects the stored response from caller mutation. */
    public TestRun {
        runId = normalized(runId);
        nodeTraces = nodeTraces == null ? List.of() : List.copyOf(nodeTraces);
        edgeTraces = edgeTraces == null ? List.of() : List.copyOf(edgeTraces);
        fixtureConsumptions = fixtureConsumptions == null ? List.of() : List.copyOf(fixtureConsumptions);
        assertionResults = assertionResults == null ? List.of() : List.copyOf(assertionResults);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        integrity = integrity == null ? Integrity.legacyUnsigned() : integrity;
        rawResponse = rawResponse == null ? null : rawResponse.deepCopy();
    }

    /**
     * Creates a backward-compatible run projection without edge summaries.
     *
     * @param runId durable run id
     * @param status terminal evidence status
     * @param evidenceClass evidence trust class
     * @param targetFingerprint frozen target fingerprint
     * @param fixtureBundleFingerprint frozen fixture fingerprint
     * @param planFingerprint effective-plan fingerprint
     * @param nodeTraces payload-free node summaries
     * @param fixtureConsumptions governed fixture consumption facts
     * @param assertionResults evaluated assertion summaries
     * @param diagnostics bounded diagnostics excluded from built-in reports
     * @param rawResponse complete authorized response
     */
    public TestRun(String runId, Status status, EvidenceClass evidenceClass,
                   String targetFingerprint, String fixtureBundleFingerprint,
                   String planFingerprint, List<NodeTrace> nodeTraces,
                   List<FixtureConsumption> fixtureConsumptions,
                   List<AssertionResult> assertionResults, List<String> diagnostics,
                   JsonNode rawResponse) {
        this(runId, status, evidenceClass, targetFingerprint, fixtureBundleFingerprint,
                planFingerprint, nodeTraces, List.of(), fixtureConsumptions,
                assertionResults, diagnostics, Integrity.legacyUnsigned(), rawResponse);
    }

    /**
     * Returns the authorized raw response without exposing mutable internal state.
     *
     * @return defensive copy because Jackson object nodes are mutable
     */
    @Override
    public JsonNode rawResponse() {
        return rawResponse == null ? null : rawResponse.deepCopy();
    }

    /**
     * Projects a testing-control-plane response without retaining node input/output in summary
     * fields.
     *
     * @param response decoded {@code bloge.testExecutionResponse.v1} or signed v2 response
     * @return immutable run projection
     */
    public static TestRun from(JsonNode response) {
        if (response == null || !response.isObject()) {
            throw new IllegalArgumentException("A test execution response object is required");
        }
        String responseVersion = response.path("schemaVersion").asText();
        if (!TestingProtocol.TEST_EXECUTION_RESPONSE_V1.equals(responseVersion)
                && !TestingProtocol.TEST_EXECUTION_RESPONSE_V2.equals(responseVersion)) {
            throw new IllegalArgumentException("Unsupported test execution response version");
        }
        JsonNode evidence = response.path("evidence");
        String runId = response.path("runId").asText(evidence.path("runId").asText());
        Status status = enumValue(Status.class, evidence.path("status").asText(), "status");
        EvidenceClass evidenceClass = enumValue(EvidenceClass.class,
                evidence.path("evidenceClass").asText(), "evidenceClass");

        List<NodeTrace> nodes = new ArrayList<>();
        evidence.path("nodeTrace").forEach(node -> {
            List<AttemptTrace> attempts = new ArrayList<>();
            node.path("attempts").forEach(attempt -> attempts.add(new AttemptTrace(
                    attempt.path("attempt").asInt(), attempt.path("status").asText(),
                    attempt.path("fidelity").asText(), attempt.path("errorCode").asText(),
                    attempt.path("durationMs").asLong())));
            nodes.add(new NodeTrace(node.path("nodeId").asText(), node.path("operatorRef").asText(),
                    node.path("status").asText(), node.path("fidelity").asText(),
                    node.path("errorCode").asText(), node.path("durationMs").asLong(),
                    node.path("invocationSiteId").asText(), node.path("graphPath").asText(),
                    node.path("correlationKey").asText(), node.path("occurrence").asInt(),
                    node.path("graphOccurrence").asInt(), attempts));
        });
        List<EdgeTrace> edges = new ArrayList<>();
        evidence.path("edgeTrace").forEach(edge -> edges.add(new EdgeTrace(
                edge.path("edgeId").asText(), edge.path("status").asText(),
                edge.path("graphPath").asText(), edge.path("correlationKey").asText(),
                edge.path("graphOccurrence").asInt(), edge.path("fromInvocationSiteId").asText(),
                edge.path("toInvocationSiteId").asText())));
        List<FixtureConsumption> fixtures = new ArrayList<>();
        evidence.path("fixtureConsumptions").forEach(fixture -> fixtures.add(new FixtureConsumption(
                fixture.path("ruleId").asText(), fixture.path("uses").asInt(),
                fixture.path("required").asBoolean(), fixture.path("status").asText())));
        List<AssertionResult> assertions = new ArrayList<>();
        evidence.path("assertionResults").forEach(assertion -> assertions.add(new AssertionResult(
                assertion.path("scope").asText(), assertion.path("path").asText(),
                assertion.path("passed").asBoolean(), bounded(assertion.path("diagnostic").asText(), 1024))));
        List<String> diagnostics = new ArrayList<>();
        evidence.path("diagnostics").forEach(value -> diagnostics.add(bounded(value.asText(), 1024)));
        Integrity integrity = TestingProtocol.TEST_EXECUTION_RESPONSE_V1.equals(responseVersion)
                ? Integrity.legacyUnsigned() : integrity(response.path("integrity"));
        if (TestingProtocol.TEST_EXECUTION_RESPONSE_V2.equals(responseVersion)
                && evidenceClass == EvidenceClass.CERTIFIABLE
                && integrity.signatureStatus() != SignatureStatus.VERIFIED) {
            throw new IllegalArgumentException("Certifiable v2 evidence must carry a verified signature");
        }
        return new TestRun(runId, status, evidenceClass, evidence.path("targetFingerprint").asText(),
                evidence.path("fixtureBundleFingerprint").asText(),
                evidence.path("planFingerprint").asText(), nodes, edges, fixtures, assertions,
                diagnostics, integrity, response);
    }

    /**
     * Indicates whether this run completed in the sole passing terminal state.
     *
     * @return true only for a terminal successful execution
     */
    public boolean passed() {
        return status == Status.PASSED;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Unknown or missing test run " + field + ": " + value, failure);
        }
    }

    private static Integrity integrity(JsonNode value) {
        if (value == null || !value.isObject()
                || !TestingProtocol.TEST_EVIDENCE_INTEGRITY_V1
                .equals(value.path("schemaVersion").asText())) {
            throw new IllegalArgumentException("A versioned test evidence integrity manifest is required");
        }
        String evidenceFingerprint = requiredFingerprint(
                value.path("evidenceFingerprint").asText(), "evidenceFingerprint");
        String projectionFingerprint = requiredFingerprint(
                value.path("projectionFingerprint").asText(), "projectionFingerprint");
        SignatureStatus status = enumValue(SignatureStatus.class,
                value.path("signatureStatus").asText(), "integrity signatureStatus");
        Projection projection = enumValue(Projection.class,
                value.path("projection").asText(), "integrity projection");
        Instant signedAt;
        try {
            signedAt = Instant.parse(value.path("signedAt").asText());
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("Invalid test evidence signing time", failure);
        }
        Integrity integrity = new Integrity(evidenceFingerprint, status,
                bounded(value.path("keyId").asText(), 255),
                bounded(value.path("algorithm").asText(), 64), signedAt,
                bounded(value.path("signature").asText(), 8192), projection,
                projectionFingerprint, value.path("independentlyVerifiable").asBoolean());
        if (status == SignatureStatus.VERIFIED && !integrity.signed()) {
            throw new IllegalArgumentException("Verified test evidence integrity is incomplete");
        }
        boolean expectedIndependent = status == SignatureStatus.VERIFIED
                && projection == Projection.FULL
                && evidenceFingerprint.equals(projectionFingerprint);
        if (integrity.independentlyVerifiable() != expectedIndependent) {
            throw new IllegalArgumentException("Test evidence independent-verification claim is inconsistent");
        }
        return integrity;
    }

    private static String requiredFingerprint(String value, String field) {
        String normalized = normalized(value);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 fingerprint");
        }
        return normalized;
    }

    private static String bounded(String value, int maximum) {
        String normalized = normalized(value);
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
