package com.leanowtech.bloge.gateway.testing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Fingerprinted test-run evidence skeleton. It deliberately distinguishes execution failure,
 * assertion failure, control-plan failure, and evidence fidelity instead of flattening all outcomes
 * into a boolean pass flag.
 *
 * @param schemaVersion evidence schema version
 * @param runId unique test-run id
 * @param status normalized ten-state run status
 * @param evidenceClass exploratory or certifiable evidence classification
 * @param executionPurpose authorized execution purpose
 * @param targetFingerprint frozen target fingerprint
 * @param fixtureBundleFingerprint frozen fixture fingerprint
 * @param planFingerprint effective execution-plan fingerprint
 * @param startedAt run start
 * @param completedAt terminal time
 * @param nodeTrace observed node facts
 * @param edgeTrace observed edge facts
 * @param fixtureConsumptions per-rule consumption facts
 * @param assertionResults assertion facts
 * @param diagnostics bounded error and warning facts
 * @param metadata suite, case, owner, and provenance metadata
 */
public record TestRunEvidence(
        String schemaVersion,
        String runId,
        Status status,
        EvidenceClass evidenceClass,
        String executionPurpose,
        String targetFingerprint,
        String fixtureBundleFingerprint,
        String planFingerprint,
        Instant startedAt,
        Instant completedAt,
        List<NodeTrace> nodeTrace,
        List<EdgeTrace> edgeTrace,
        List<FixtureConsumption> fixtureConsumptions,
        List<AssertionResult> assertionResults,
        List<String> diagnostics,
        Map<String, Object> metadata
) {
    /** Current test-run evidence protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testRunEvidence.v1";

    /** Normalized test-run lifecycle and terminal states. */
    public enum Status {
        PASSED,
        ASSERTION_FAILED,
        EXECUTION_FAILED,
        CONTROL_PLAN_REJECTED,
        FIXTURE_UNMATCHED,
        FIXTURE_UNUSED,
        CONTROL_PLAN_UNAVAILABLE,
        EVIDENCE_INCOMPLETE,
        CANCELLED,
        TIMED_OUT
    }

    /** Evidence trust class consumed by correctness workbooks and release gates. */
    public enum EvidenceClass {
        EXPLORATORY,
        CERTIFIABLE
    }

    /** Creates immutable evidence collections. */
    public TestRunEvidence {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        runId = trimmed(runId);
        status = status == null ? Status.EVIDENCE_INCOMPLETE : status;
        evidenceClass = evidenceClass == null ? EvidenceClass.EXPLORATORY : evidenceClass;
        executionPurpose = trimmed(executionPurpose);
        targetFingerprint = trimmed(targetFingerprint);
        fixtureBundleFingerprint = trimmed(fixtureBundleFingerprint);
        planFingerprint = trimmed(planFingerprint);
        nodeTrace = immutableList(nodeTrace);
        edgeTrace = immutableList(edgeTrace);
        fixtureConsumptions = immutableList(fixtureConsumptions);
        assertionResults = immutableList(assertionResults);
        diagnostics = immutableList(diagnostics);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * Node execution fact.
     *
     * @param nodeId node id
     * @param operatorRef resolved operator
     * @param status SUCCESS, FAILED, TIMEOUT, SKIPPED, or MOCKED execution status
     * @param fidelity OUTPUT_LEVEL, PROTOCOL_DERIVED, TRANSPORT_LEVEL, or REPLAYED
     * @param input sanitized node input
     * @param output sanitized node output
     * @param errorCode normalized error code
     * @param durationMs observed duration
     * @param invocationSiteId stable structural invocation-site id
     * @param graphPath stable path of the graph that owns this occurrence
     * @param correlationKey foreach, loop, or business correlation coordinate
     * @param occurrence one-based resolver binding occurrence; zero means legacy unknown
     * @param attempts ordered one-based delegate-attempt facts for this occurrence
     */
    public record NodeTrace(String nodeId, String operatorRef, String status, String fidelity,
                            Object input, Object output, String errorCode, long durationMs,
                            String invocationSiteId, String graphPath, String correlationKey,
                            int occurrence, List<AttemptTrace> attempts) {
        /** Backward-compatible constructor for v1 producers without occurrence coordinates. */
        public NodeTrace(String nodeId, String operatorRef, String status, String fidelity,
                         Object input, Object output, String errorCode, long durationMs) {
            this(nodeId, operatorRef, status, fidelity, input, output, errorCode, durationMs,
                    "", "", "", 0, List.of());
        }

        /** Normalizes node trace labels. */
        public NodeTrace {
            nodeId = trimmed(nodeId);
            operatorRef = trimmed(operatorRef);
            status = trimmed(status);
            fidelity = trimmed(fidelity);
            errorCode = trimmed(errorCode);
            invocationSiteId = trimmed(invocationSiteId);
            graphPath = trimmed(graphPath);
            correlationKey = trimmed(correlationKey);
            if (occurrence < 0) {
                throw new IllegalArgumentException("occurrence must be >= 0");
            }
            attempts = immutableList(attempts);
        }
    }

    /**
     * One actual delegate call within a node occurrence.
     *
     * @param attempt one-based attempt number
     * @param status SUCCESS, FAILED, TIMEOUT, or MOCKED
     * @param fidelity REAL, OUTPUT_LEVEL, PROTOCOL_DERIVED, TRANSPORT_LEVEL, or REPLAYED
     * @param input sanitized attempt input
     * @param output sanitized attempt output
     * @param errorCode normalized failure code
     * @param durationMs observed attempt duration
     */
    public record AttemptTrace(int attempt, String status, String fidelity, Object input,
                               Object output, String errorCode, long durationMs) {
        /** Normalizes labels and rejects negative wire values. */
        public AttemptTrace {
            if (attempt < 0) {
                throw new IllegalArgumentException("attempt must be >= 0");
            }
            status = trimmed(status);
            fidelity = trimmed(fidelity);
            errorCode = trimmed(errorCode);
            if (durationMs < 0) {
                throw new IllegalArgumentException("durationMs must be >= 0");
            }
        }
    }

    /**
     * Edge transfer fact.
     *
     * @param edgeId stable edge id
     * @param status transfer status
     * @param value sanitized transferred value
     */
    public record EdgeTrace(String edgeId, String status, Object value) {
        /** Normalizes edge trace labels. */
        public EdgeTrace {
            edgeId = trimmed(edgeId);
            status = trimmed(status);
        }
    }

    /**
     * Fixture-use fact.
     *
     * @param ruleId fixture rule id
     * @param uses observed uses
     * @param required whether consumption was mandatory
     * @param status consumption status
     */
    public record FixtureConsumption(String ruleId, int uses, boolean required, String status) {
        /** Normalizes fixture identifiers. */
        public FixtureConsumption {
            ruleId = trimmed(ruleId);
            status = trimmed(status);
        }
    }

    /**
     * Assertion evaluation fact.
     *
     * @param scope assertion scope
     * @param path assertion JSON Pointer
     * @param passed whether the assertion passed
     * @param expected expected value
     * @param actual observed value
     * @param diagnostic failure diagnostic
     */
    public record AssertionResult(String scope, String path, boolean passed, Object expected,
                                  Object actual, String diagnostic) {
        /** Normalizes assertion labels. */
        public AssertionResult {
            scope = trimmed(scope);
            path = trimmed(path);
            diagnostic = diagnostic == null ? "" : diagnostic;
        }
    }

    private static String defaulted(String value, String fallback) {
        String normalized = trimmed(value);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
