package com.leanowtech.bloge.gateway.solution.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddExecutionService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Controlled execution seam shared by Feature suite orchestration and the existing Agent TDD
 * rehearsal kernel.
 *
 * <p>The seam accepts protected material only in memory and returns aggregate, payload-free facts.
 * The {@link #using(AgentTddExecutionService, ObjectMapper)} adapter executes the same frozen graph,
 * dependency behavior compiler, Oracle comparison, and zero-egress guard as
 * {@code rg.feature.rehearse}.</p>
 */
@FunctionalInterface
public interface FeatureControlledCaseRunner {
    /** Runs all cases against one exact Feature graph and returns payload-free observations. */
    RunResult run(RunRequest request, IntegrationRequestContext identity);

    /** Creates the production adapter over the existing Agent TDD Feature rehearsal kernel. */
    static FeatureControlledCaseRunner using(AgentTddExecutionService execution, ObjectMapper mapper) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(mapper, "mapper");
        return (request, identity) -> {
            ObjectNode arguments = mapper.createObjectNode();
            // The logical Feature and its implementation graph are separate assets. Execute the
            // exact graph reference that engineering will later bind.
            arguments.put("featureRef", request.evaluationRef());
            ArrayNode refs = arguments.putArray("libraryRefs");
            request.libraryRefs().forEach(refs::add);
            ArrayNode rows = arguments.putObject("cases").putArray("rows");
            for (FeatureControlledSuiteDefinition.Case testCase : request.cases()) {
                ObjectNode row = rows.addObject();
                row.put("caseId", testCase.caseId());
                row.put("layer", "unit");
                row.put("category", "REGRESSION");
                row.put("oracleOwner", "platform-feature-engineering");
                row.set("given", testCase.givenInputs());
                row.set("expect", testCase.expectedOutput());
                ObjectNode stubs = row.putObject("stubs");
                testCase.nodeBehaviors().forEach(behavior ->
                        stubs.set(behavior.nodeId(), behavior.behavior()));
            }
            Map<String, Object> response = execution.rehearse(arguments, identity);
            JsonNode responseNode = mapper.valueToTree(response);
            List<CaseResult> caseResults = new ArrayList<>();
            responseNode.path("cases").forEach(result -> {
                LinkedHashSet<String> observed = new LinkedHashSet<>();
                result.path("mockedNodeIds").forEach(node -> observed.add("node:" + node.asText()));
                result.path("realNodeIds").forEach(node -> observed.add("node:" + node.asText()));
                caseResults.add(new CaseResult(
                        result.path("caseId").asText(), result.path("verdict").asText(),
                        List.copyOf(observed)));
            });
            return new RunResult(
                    responseNode.path("evidenceFingerprint").asText(),
                    responseNode.path("draftRevision").asLong(-1),
                    caseResults, responseNode.path("realExternalCalls").asInt(-1));
        };
    }

    /** In-memory request containing material resolved from one exact protected receipt. */
    record RunRequest(
            String featureRef,
            String evaluationRef,
            long expectedGraphRevision,
            List<String> libraryRefs,
            List<FeatureControlledSuiteDefinition.Case> cases
    ) {
        /** Freezes the exact suite execution subject. */
        public RunRequest {
            featureRef = required(featureRef, "featureRef");
            evaluationRef = required(evaluationRef, "evaluationRef");
            if (expectedGraphRevision < 0) {
                throw new IllegalArgumentException("expectedGraphRevision must be non-negative");
            }
            libraryRefs = libraryRefs == null ? List.of() : List.copyOf(libraryRefs);
            cases = cases == null ? List.of() : List.copyOf(cases);
            if (cases.isEmpty()) throw new IllegalArgumentException("cases are required");
        }
    }

    /** Payload-free aggregate emitted by the existing rehearsal kernel. */
    record RunResult(
            String executionEvidenceFingerprint,
            long graphRevision,
            List<CaseResult> cases,
            int realExternalCalls
    ) {
        /** Validates the runner result before it can become suite evidence. */
        public RunResult {
            executionEvidenceFingerprint = required(
                    executionEvidenceFingerprint, "executionEvidenceFingerprint");
            if (!executionEvidenceFingerprint.matches("sha256:[a-f0-9]{64}")) {
                throw new IllegalArgumentException("executionEvidenceFingerprint must be a SHA-256 fingerprint");
            }
            if (graphRevision < 0 || realExternalCalls < 0) {
                throw new IllegalArgumentException("runner counters must be non-negative");
            }
            cases = cases == null ? List.of() : List.copyOf(cases);
            if (cases.isEmpty()) throw new IllegalArgumentException("runner cases are required");
        }
    }

    /** One case verdict and the structural targets actually observed during execution. */
    record CaseResult(String caseId, String verdict, List<String> observedCoverageTargets) {
        /** Normalizes the payload-free observation. */
        public CaseResult {
            caseId = required(caseId, "caseId");
            verdict = required(verdict, "verdict");
            observedCoverageTargets = observedCoverageTargets == null ? List.of()
                    : observedCoverageTargets.stream().map(value -> required(value, "coverage target"))
                    .distinct().sorted().toList();
        }

        /** @return whether the existing honest rehearsal verdict passed */
        public boolean passed() {
            return verdict.endsWith("_PASS");
        }
    }

    /** Returns an adapter that fails closed when runtime wiring is absent. */
    static FeatureControlledCaseRunner unavailable() {
        return (request, identity) -> {
            throw new AgentTddToolException(
                    "FEATURE_SUITE_RUNNER_UNAVAILABLE", "Controlled Feature execution is unavailable.");
        };
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
