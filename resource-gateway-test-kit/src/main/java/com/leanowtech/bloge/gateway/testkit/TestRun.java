package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

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
 * @param fixtureConsumptions fixture-use summaries
 * @param assertionResults payload-free assertion summaries
 * @param diagnostics bounded server diagnostics, excluded from built-in reports
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
        List<FixtureConsumption> fixtureConsumptions,
        List<AssertionResult> assertionResults,
        List<String> diagnostics,
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

    /**
     * Payload-free node execution summary.
     *
     * @param nodeId graph node id
     * @param operatorRef resolved operator reference
     * @param status execution observation such as MOCKED or REAL
     * @param fidelity observed fixture fidelity
     * @param errorCode normalized error code
     * @param durationMs observed duration in milliseconds
     */
    public record NodeTrace(String nodeId, String operatorRef, String status, String fidelity,
                            String errorCode, long durationMs) {
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
        fixtureConsumptions = fixtureConsumptions == null ? List.of() : List.copyOf(fixtureConsumptions);
        assertionResults = assertionResults == null ? List.of() : List.copyOf(assertionResults);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        rawResponse = rawResponse == null ? null : rawResponse.deepCopy();
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
     * @param response decoded {@code bloge.testExecutionResponse.v1}
     * @return immutable run projection
     */
    public static TestRun from(JsonNode response) {
        if (response == null || !response.isObject()) {
            throw new IllegalArgumentException("A test execution response object is required");
        }
        JsonNode evidence = response.path("evidence");
        String runId = response.path("runId").asText(evidence.path("runId").asText());
        Status status = enumValue(Status.class, evidence.path("status").asText(), "status");
        EvidenceClass evidenceClass = enumValue(EvidenceClass.class,
                evidence.path("evidenceClass").asText(), "evidenceClass");

        List<NodeTrace> nodes = new ArrayList<>();
        evidence.path("nodeTrace").forEach(node -> nodes.add(new NodeTrace(
                node.path("nodeId").asText(), node.path("operatorRef").asText(),
                node.path("status").asText(), node.path("fidelity").asText(),
                node.path("errorCode").asText(), node.path("durationMs").asLong())));
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
        return new TestRun(runId, status, evidenceClass, evidence.path("targetFingerprint").asText(),
                evidence.path("fixtureBundleFingerprint").asText(),
                evidence.path("planFingerprint").asText(), nodes, fixtures, assertions, diagnostics,
                response);
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

    private static String bounded(String value, int maximum) {
        String normalized = normalized(value);
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
