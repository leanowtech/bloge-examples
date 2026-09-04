package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.solution.InstructionDispatchChannel;
import com.leanowtech.bloge.gateway.solution.ScenarioTreeEvaluator;
import com.leanowtech.bloge.gateway.solution.SolutionContractException;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.solution.SolutionExecutionService;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Runs Scenario contract tests and approved Solution GOLDEN baselines with zero real egress. */
public final class SolutionTestingService {
    /** Durable payload-free Solution baseline evidence kind. */
    public static final String SOLUTION_EVIDENCE = "SOLUTION_EVIDENCE";
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private final AgentTddStateRepository states;
    private final SolutionEntityRegistry registry;
    private final ObjectMapper mapper;
    private final SolutionExecutionService execution;

    /** Creates a testing pyramid over the canonical entities and shared approved-case repository. */
    public SolutionTestingService(
            AgentTddStateRepository states,
            SolutionEntityRegistry registry,
            ObjectMapper mapper,
            InstructionDispatchChannel instructionChannel) {
        this.states = Objects.requireNonNull(states, "states");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.execution = new SolutionExecutionService(registry, mapper,
                Objects.requireNonNull(instructionChannel, "instructionChannel"));
    }

    /** Evaluates explicit Feature values against expected Scenario outlet subsets. */
    public Map<String, Object> testScenario(String scopeKey, String scenarioRef, JsonNode cases) {
        if (cases == null || !cases.isArray() || cases.isEmpty()) throw schemaFailure();
        List<Map<String, Object>> byCase = new ArrayList<>();
        ScenarioTreeEvaluator evaluator = new ScenarioTreeEvaluator(registry, 8);
        cases.forEach(row -> {
            String caseId = requiredText(row, "caseId");
            if (!row.path("given").isObject() || !row.path("expect").isObject()) throw schemaFailure();
            ScenarioTreeEvaluator.Outcome result;
            try {
                result = evaluator.evaluate(scopeKey, scenarioRef, row.path("given"));
            } catch (SolutionContractException failure) {
                throw new AgentTddToolException(failure.code(), failure.getMessage());
            }
            ObjectNode actual = mapper.createObjectNode();
            actual.put("outletKind", result.outletKind());
            actual.put("ref", result.ref());
            actual.put("terminalKind", result.terminalKind());
            actual.set("bind", mapper.valueToTree(result.bind()));
            boolean pass = contains(actual, row.path("expect"));
            byCase.add(Map.of("caseId", caseId, "hitRuleId", result.rulePath().getLast(),
                    "outlet", actual, "pass", pass));
        });
        long passed = byCase.stream().filter(row -> Boolean.TRUE.equals(row.get("pass"))).count();
        return Map.of("scenarioRef", scenarioRef, "byCase", byCase,
                "passed", passed, "failed", byCase.size() - passed, "realExternalCalls", 0);
    }

    /** Runs every approved ACTIVE GOLDEN row against the pure Solution and persists one evidence view. */
    public Map<String, Object> baseline(
            String scopeKey, String solutionRef, String caseSetRef, String side) {
        String normalizedSide = side == null ? "" : side.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("RED", "GREEN").contains(normalizedSide)) throw schemaFailure();
        AgentTddStoredAsset caseSet = states.find(scopeKey, AgentTddMutationService.CASE_SET, caseSetRef)
                .filter(asset -> solutionRef.equals(asset.data().path("toolRef").asText()))
                .orElseThrow(() -> new AgentTddToolException(
                        "DRAFT_NOT_FOUND", "Solution case set was not found."));
        SolutionEntityRegistry.RegisteredEntity solution;
        try {
            solution = registry.requireRegisteredSolution(scopeKey, solutionRef);
        } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            throw new AgentTddToolException("REFERENCE_UNRESOLVED", "A Solution is unavailable.");
        }
        return states.executeAtomically(() -> baselineLocked(
                scopeKey, solutionRef, caseSetRef, normalizedSide, caseSet.revision(), solution));
    }

    private Map<String, Object> baselineLocked(
            String scopeKey,
            String solutionRef,
            String caseSetRef,
            String normalizedSide,
            long expectedRevision,
            SolutionEntityRegistry.RegisteredEntity expectedSolution) {
        AgentTddStoredAsset caseSet = states.lockRevision(
                scopeKey, AgentTddMutationService.CASE_SET, caseSetRef, expectedRevision);
        if (!solutionRef.equals(caseSet.data().path("toolRef").asText())) {
            throw new AgentTddToolException("DRAFT_NOT_FOUND", "Solution case set was not found.");
        }
        AgentTddStoredAsset solutionAsset = states.lockRevision(scopeKey,
                SolutionEntityRegistry.SOLUTION, solutionRef, expectedSolution.revision());
        if (!expectedSolution.contractFingerprint().equals(
                solutionAsset.data().path("contractFingerprint").asText())) {
            throw new AgentTddToolException("GATE_REJECTED", "Solution changed during baseline execution.");
        }
        List<JsonNode> golden = new ArrayList<>();
        caseSet.data().path("rows").forEach(row -> {
            if ("GOLDEN".equals(row.path("category").asText())
                    && "ACTIVE".equals(row.path("lifecycle").asText())
                    && row.path("expect").isObject()) golden.add(row);
        });
        if (golden.isEmpty()) throw new AgentTddToolException(
                "GOLDEN_REQUIRES_APPROVAL", "Approved Solution GOLDEN cases are required.");
        List<Map<String, Object>> cases = new ArrayList<>();
        List<Map<String, Object>> backlog = new ArrayList<>();
        LinkedHashMap<String, Integer> hitDistribution = new LinkedHashMap<>();
        for (JsonNode row : golden) {
            String caseId = requiredText(row, "caseId");
            SolutionExecutionService.ExecutionResult result;
            try {
                result = execution.simulate(scopeKey, solutionRef, row.path("given"));
            } catch (SolutionContractException failure) {
                throw new AgentTddToolException(failure.code(), failure.getMessage());
            }
            ObjectNode actual = mapper.createObjectNode();
            actual.set("result", mapper.valueToTree(result.result()));
            actual.put("reasoning", result.reasoning());
            actual.put("instructionRef", result.instructionRef());
            boolean pass = contains(actual, row.path("expect"));
            String verdict = normalizedSide + "_" + (pass ? "PASS" : "FAIL");
            cases.add(Map.of("caseId", caseId, "verdict", verdict,
                    "instructionRef", result.instructionRef(), "rulePath", result.rulePath()));
            hitDistribution.merge(result.instructionRef().isBlank() ? "TERMINAL" : result.instructionRef(), 1,
                    Integer::sum);
            if (!pass) backlog.add(Map.of("caseId", caseId, "reason", verdict,
                    "owner", row.path("oracleOwner").asText("business-owner")));
        }
        long persistedCaseRevision = caseSet.revision();
        if ("GREEN".equals(normalizedSide)) {
            java.util.Set<String> passing = cases.stream()
                    .filter(row -> "GREEN_PASS".equals(row.get("verdict")))
                    .map(row -> row.get("caseId").toString())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            ObjectNode updated = (ObjectNode) caseSet.data().deepCopy();
            com.fasterxml.jackson.databind.node.ArrayNode rows = updated.putArray("rows");
            caseSet.data().path("rows").forEach(raw -> {
                ObjectNode row = (ObjectNode) raw.deepCopy();
                if (passing.contains(row.path("caseId").asText())) row.put("qualityState", "READY");
                rows.add(row);
            });
            persistedCaseRevision = states.saveIfRevision(scopeKey, AgentTddMutationService.CASE_SET,
                    caseSetRef, caseSet.revision(), updated).revision();
        }
        String goldenSetId = VisualBundleFingerprint.fromCanonicalValue(mapper,
                golden.stream().map(JsonNode::deepCopy).toList(), MAX_BYTES);
        ObjectNode evidence = mapper.createObjectNode();
        evidence.put("solutionRef", solutionRef);
        evidence.put("caseSetRef", caseSetRef);
        evidence.put("caseSetRevision", persistedCaseRevision);
        evidence.put("solutionRevision", expectedSolution.revision());
        evidence.put("solutionContractFingerprint", expectedSolution.contractFingerprint());
        evidence.put("goldenSetId", goldenSetId);
        evidence.put("side", normalizedSide);
        evidence.set("cases", mapper.valueToTree(cases));
        evidence.set("businessBacklog", mapper.valueToTree(backlog));
        evidence.set("hitDistribution", mapper.valueToTree(hitDistribution));
        evidence.put("realExternalCalls", 0);
        AgentTddStoredAsset stored = states.save(scopeKey, SOLUTION_EVIDENCE, solutionRef, evidence);
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("solutionRef", solutionRef);
        response.put("caseSetRef", caseSetRef);
        response.put("caseSetRevision", persistedCaseRevision);
        response.put("solutionRevision", expectedSolution.revision());
        response.put("solutionContractFingerprint", expectedSolution.contractFingerprint());
        response.put("goldenSetId", goldenSetId);
        response.put("evidenceRef", stored.assetRef() + "@" + stored.revision());
        response.put("side", normalizedSide);
        response.put("byLayer", Map.of("integration", Map.of(
                "pass", cases.size() - backlog.size(), "fail", backlog.size())));
        response.put("cases", cases);
        response.put("businessBacklog", backlog);
        response.put("realExternalCalls", 0);
        response.put("status", backlog.isEmpty() ? "GO" : "NO_GO");
        return Map.copyOf(response);
    }

    private static boolean contains(JsonNode actual, JsonNode expected) {
        if (expected.isObject()) {
            var fields = expected.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!actual.has(field.getKey()) || !contains(actual.path(field.getKey()), field.getValue())) {
                    return false;
                }
            }
            return true;
        }
        return actual.equals(expected);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node == null ? "" : node.path(field).asText().trim();
        if (value.isBlank()) throw schemaFailure();
        return value;
    }

    private static AgentTddToolException schemaFailure() {
        return new AgentTddToolException(
                "SCHEMA_NONCONFORMANT", "Solution test cases do not match the declared schema.");
    }
}
