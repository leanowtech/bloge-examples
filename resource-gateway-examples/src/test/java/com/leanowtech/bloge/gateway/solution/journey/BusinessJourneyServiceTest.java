package com.leanowtech.bloge.gateway.solution.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddReviewService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.BusinessFactSemanticContract;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.ScenarioContract;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves that business navigation and complete GOLDEN governance share one current asset line. */
class BusinessJourneyServiceTest {
    private static final String SCOPE = "tenant-a|org-a|project-a|test|sg";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
    private final SolutionEntityRegistry registry = new SolutionEntityRegistry(states, mapper);
    private final BusinessJourneyService journeys = new BusinessJourneyService(states, mapper);
    private final BusinessGoldenService golden = new BusinessGoldenService(states, mapper);

    @BeforeEach
    void defineCurrentBusinessContracts() {
        BusinessFactSemanticContract semantics = new BusinessFactSemanticContract(
                BusinessFactSemanticContract.SCHEMA_VERSION,
                "ride.cancel.party", "判断取消责任", "ride-cancellation", "ride-order",
                mapper.createArrayNode(),
                mapper.valueToTree(Map.of("type", "enum", "values", List.of("passenger", "driver"))),
                "CANCELLATION_OCCURRED_AT", "REQUIRE_HUMAN_REVIEW", "PLATFORM",
                "responsibility-center", mapper.valueToTree(Map.of("mode", "AS_OF_EVENT")), "READ", "ACTIVE");
        registry.upsertFeature(SCOPE, new FeatureContract("responsibility.party",
                mapper.valueToTree(Map.of("type", Map.of("enum", List.of("passenger", "driver")))),
                FeatureContract.EvaluationKind.API, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("orderId", "string")), "resource:responsibility#$.party",
                "", "", "取消责任方", semantics));
        registry.upsertInstruction(SCOPE, new InstructionContract("ins:uphold",
                mapper.valueToTree(Map.of("orderId", "string")), mapper.valueToTree(Map.of(
                "result", Map.of("type", Map.of("fields", Map.of(
                        "decision", Map.of("enum", List.of("UPHELD"))))), "reasoning", "required")),
                InstructionContract.Effect.READ, "tool:uphold", null, "维持费用"));
        registry.upsertScenario(SCOPE, new ScenarioContract("scn:cancel", List.of("party"),
                ScenarioContract.HitPolicy.UNIQUE, List.of(new ScenarioContract.Rule("R1",
                mapper.valueToTree(Map.of("party", Map.of("eq", "passenger"))),
                new ScenarioContract.Outlet(ScenarioContract.OutletKind.INSTRUCTION,
                        "ins:uphold", Map.of("orderId", "orderId"), ""))),
                new ScenarioContract.Outlet(ScenarioContract.OutletKind.TERMINAL, "", Map.of(), "ESCALATE")));
        registry.upsertSolution(SCOPE, new SolutionContract("sol:cancel", "处理取消费争议",
                Map.of("party", "responsibility.party"),
                "scn:cancel", List.of("ins:uphold"), "caseSet:journey"), true);
    }

    @Test
    void derivesStagesFromAssetsAndRejectsStaleOrOutOfOrderActions() {
        Map<String, Object> started = journeys.start(startRequest("journey-start-1"), agent());
        String ref = started.get("journeyRef").toString();
        assertThat(started).containsEntry("stage", "DISCOVERING");

        ObjectNode featureAction = action(ref, 1);
        journeys.executeAction("rg.feature.define", featureAction, agent(),
                () -> Map.of("featureId", "responsibility.party", "revision", 1));
        Map<String, Object> rules = journeys.next(next(ref, 2), agent());
        assertThat(rules).containsEntry("stage", "DEFINING_RULES");

        assertThatThrownBy(() -> journeys.executeAction("rg.solution.compose", action(ref, 2), agent(), Map::of))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("JOURNEY_ACTION_NOT_ALLOWED"));
        assertThatThrownBy(() -> journeys.next(next(ref, 1), agent()))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("JOURNEY_REVISION_STALE"));
    }

    @Test
    void proposesSummaryOnlyCasesAndRequiresIndependentHumanApprovalBeforeTesting() {
        String ref = journeys.start(startRequest("journey-start-2"), agent()).get("journeyRef").toString();
        associate(ref, 1, "rg.feature.define", Map.of("featureId", "responsibility.party", "revision", 1));
        associate(ref, 2, "rg.scenario.define", Map.of("scenarioId", "scn:cancel", "revision", 1));
        associate(ref, 3, "rg.instruction.define", Map.of("instructionId", "ins:uphold", "revision", 1));
        Map<String, Object> composing = journeys.next(next(ref, 4), agent());
        String context = composing.get("solutionContextFingerprint").toString();
        ObjectNode compose = action(ref, 4).put("solutionContextFingerprint", context);
        journeys.executeAction("rg.solution.compose", compose, agent(),
                () -> Map.of("solutionRef", "sol:cancel", "revision", 1));

        ObjectNode proposal = action(ref, 5).put("solutionRef", "sol:cancel")
                .put("idempotencyKey", "golden-proposal-1");
        proposal.set("cases", mapper.valueToTree(List.of(Map.of(
                "caseId", "g1", "businessIntent", "乘客超时取消由乘客承担",
                "givenFacts", List.of(Map.of("factName", "取消责任方", "value", "passenger")),
                "dependencyAssumptions", List.of(Map.of("capabilityName", "维持费用",
                        "outcome", "RETURNS", "value", Map.of("result", Map.of("decision", "UPHELD"),
                                "reasoning", "责任在乘客"))),
                "expectedOutcome", Map.of("result", Map.of("decision", "UPHELD"),
                        "reasoningClass", "责任在乘客"), "oracleOwner", "cx-policy"))));
        Map<String, Object> proposed = journeys.executeAction("rg.solution.golden.propose", proposal, agent(),
                () -> golden.propose(proposal, agent()));

        assertThat(proposed.toString()).doesNotContain("passenger", "UPHELD", "责任在乘客");
        assertThat(journeys.next(next(ref, 6), agent())).containsEntry("stage", "WAITING_GOLDEN_APPROVAL");
        Map<?, ?> summary = (Map<?, ?>) ((List<?>) proposed.get("caseSummaries")).getFirst();
        AgentTddStoredAsset approved = new AgentTddReviewService(states).approveOracle(
                proposed.get("caseSetRef").toString(), "g1", 1,
                summary.get("goldenCaseFingerprint").toString(), reviewer());
        assertThat(approved.data().at("/rows/0/lifecycle").asText()).isEqualTo("ACTIVE");
        assertThat(journeys.next(next(ref, 6), agent())).containsEntry("stage", "TESTING");
    }

    @Test
    void changesSolutionContextWhenAnAssociatedContractChanges() {
        String ref = journeys.start(startRequest("journey-start-3"), agent()).get("journeyRef").toString();
        associate(ref, 1, "rg.feature.define", Map.of("featureId", "responsibility.party", "revision", 1));
        associate(ref, 2, "rg.scenario.define", Map.of("scenarioId", "scn:cancel", "revision", 1));
        associate(ref, 3, "rg.instruction.define", Map.of("instructionId", "ins:uphold", "revision", 1));
        String before = journeys.next(next(ref, 4), agent()).get("solutionContextFingerprint").toString();

        registry.upsertInstruction(SCOPE, new InstructionContract("ins:uphold",
                mapper.valueToTree(Map.of("orderId", "string")), mapper.valueToTree(Map.of(
                "result", Map.of("type", Map.of("fields", Map.of(
                        "decision", Map.of("enum", List.of("UPHELD", "REVIEW"))))), "reasoning", "required")),
                InstructionContract.Effect.READ, "tool:uphold", null, "维持费用"));

        String after = journeys.next(next(ref, 4), agent()).get("solutionContextFingerprint").toString();
        assertThat(after).isNotEqualTo(before);
        ObjectNode stale = action(ref, 4).put("solutionContextFingerprint", before);
        assertThatThrownBy(() -> journeys.executeAction("rg.solution.compose", stale, agent(), Map::of))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("SOLUTION_CONTEXT_STALE"));
    }

    private void associate(String ref, long revision, String tool, Map<String, Object> result) {
        journeys.executeAction(tool, action(ref, revision), agent(), () -> result);
    }

    private ObjectNode startRequest(String key) {
        return mapper.createObjectNode().put("intentKind", "CREATE_SOLUTION")
                .put("businessGoal", "处理取消费争议").put("idempotencyKey", key);
    }

    private ObjectNode action(String ref, long revision) {
        return mapper.createObjectNode().put("journeyRef", ref).put("expectedJourneyRevision", revision);
    }

    private ObjectNode next(String ref, long revision) {
        return mapper.createObjectNode().put("journeyRef", ref).put("expectedRevision", revision);
    }

    private static IntegrationRequestContext agent() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                "WORKLOAD", "codex", "INTERNAL", "AGENT_TDD_AUTHORING", "corr-agent");
    }

    private static IntegrationRequestContext reviewer() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                "HUMAN", "reviewer", "INTERNAL", "AGENT_TDD_GOVERNANCE", "corr-review");
    }
}
