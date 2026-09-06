package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.BusinessFactSemanticContract;
import com.leanowtech.bloge.gateway.solution.BusinessInstructionSemanticContract;
import com.leanowtech.bloge.gateway.solution.BusinessSolutionSemanticContract;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.ScenarioContract;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.solution.journey.BusinessGoldenContractGuard;
import com.leanowtech.bloge.gateway.solution.journey.BusinessGoldenMaterialStore;
import com.leanowtech.bloge.gateway.solution.journey.BusinessGoldenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies complete business cases, rather than compiled fixtures, form the human approval. */
class AgentTddReviewBusinessGoldenTest {
    private static final String SCOPE = "tenant-a|org-a|project-a|test|sg";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
    private final SolutionEntityRegistry registry = new SolutionEntityRegistry(states, mapper);
    private final TestMaterialStore materials = new TestMaterialStore(mapper);
    private final BusinessGoldenService golden = new BusinessGoldenService(states, mapper, materials);
    private final AgentTddReviewService reviews = new AgentTddReviewService(states, materials);

    @BeforeEach
    void defineSolution() {
        registry.upsertFeature(SCOPE, feature("responsibility.party", "取消责任方", ""));
        registry.upsertFeature(SCOPE, feature("dispute.orderSelected", "争议订单", ""));
        registry.upsertInstruction(SCOPE, instruction("", "维持取消费", "WAIVED"));
        registry.upsertScenario(SCOPE, scenario());
        registry.upsertSolution(SCOPE, solution("处理取消费争议",
                List.of("ride.cancel.waive")), true);
    }

    @Test
    void reviewsOnlyOriginalBusinessFieldsAndBindsTheirCompleteFingerprint() {
        Map<String, Object> proposed = golden.propose(proposal(), agent());
        String caseSetRef = proposed.get("caseSetRef").toString();
        Map<String, Object> review = reviews.oracleReview(caseSetRef, "g1", 1, reviewer());

        assertThat(review).containsKeys("caseSetRef", "caseId", "revision", "businessIntent",
                        "givenFacts", "dependencyAssumptions", "expectedOutcome", "oracleOwner",
                        "proposedBy", "proposalFingerprint")
                .doesNotContainKeys("intent", "given", "stubs", "expect",
                        "controlledAssumptions", "controlledAssumptionPlanFingerprint",
                        "businessContractVector");
        assertThat(mapper.valueToTree(review).path("businessIntent").asText())
                .isEqualTo("乘客无责时免除取消费");
        assertThat(review.toString()).doesNotContain("ins:refund", "scn:cancel", "party=");

        JsonNode material = materials.onlyPayload();
        assertThat(material).hasSize(8);
        assertThat(material.propertyStream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder(
                "caseId", "businessIntent", "givenFacts", "dependencyAssumptions",
                "expectedOutcome", "oracleOwner", "businessCaseFingerprint",
                "goldenCaseFingerprint");
        assertThat(material.toString()).doesNotContain("controlledAssumptions", "stubs",
                "businessContractVector", "controlledAssumptionPlanFingerprint");
        JsonNode metadata = states.find(SCOPE, AgentTddMutationService.CASE_SET, caseSetRef)
                .orElseThrow().data().at("/rows/0");
        assertThat(metadata.toString()).doesNotContain("scn:cancel", "party=")
                .doesNotContain("controlledAssumptionPlanFingerprint", "featureValuesFingerprint",
                        "dependencyPlanFingerprint", "frozenContextFingerprint");
        assertThat(metadata.path("businessContractVector")).allSatisfy(coordinate ->
                assertThat(coordinate.propertyStream().map(Map.Entry::getKey).toList())
                        .containsExactlyInAnyOrder(
                                "assetKind", "assetRef", "semanticKey", "contractFingerprint"));
        assertThat(metadata.path("businessContractVector")).hasSize(4);
        assertThat(metadata.path("businessContractVector")).anySatisfy(coordinate -> {
            assertThat(coordinate.path("assetKind").asText()).isEqualTo("SOLUTION");
            assertThat(coordinate.path("assetRef").asText()).isEqualTo("sol:cancel");
            assertThat(coordinate.path("semanticKey").asText())
                    .isEqualTo("ride.cancel.dispute.solution");
            assertThat(coordinate.path("contractFingerprint").asText())
                    .isEqualTo(registry.requireRegisteredSolution(
                            SCOPE, "sol:cancel").contractFingerprint());
            assertThat(coordinate.has("revision")).isFalse();
        });

        AgentTddStoredAsset approved = reviews.approveOracle(caseSetRef, "g1", 1,
                review.get("proposalFingerprint").toString(), reviewer());
        assertThat(approved.data().at("/rows/0/lifecycle").asText()).isEqualTo("ACTIVE");
        assertThat(approved.data().at("/rows/0/proposedOracle/status").asText())
                .isEqualTo("APPROVED");
        assertThat(approved.data().at("/rows/0").has("expect")).isFalse();
        assertThat(approved.data().at("/rows/0/materialReceipt/revision").asLong()).isEqualTo(2);
    }

    @Test
    void keepsApprovalAcrossBindingRevisionButRejectsChangedBusinessResultDomain() {
        Map<String, Object> proposed = golden.propose(proposal(), agent());
        String caseSetRef = proposed.get("caseSetRef").toString();
        Map<String, Object> review = reviews.oracleReview(caseSetRef, "g1", 1, reviewer());
        AgentTddStoredAsset approved = reviews.approveOracle(caseSetRef, "g1", 1,
                review.get("proposalFingerprint").toString(), reviewer());
        JsonNode row = approved.data().at("/rows/0");

        var rebound = registry.upsertInstruction(
                SCOPE, instruction("operator:refund-v2", "维持取消费", "WAIVED"));
        assertThat(rebound.revision()).isEqualTo(2);
        assertThat(BusinessGoldenContractGuard.isCurrent(states, SCOPE, row)).isTrue();
        assertThat(states.find(SCOPE, AgentTddMutationService.CASE_SET, caseSetRef)
                .orElseThrow().data().at("/rows/0/lifecycle").asText()).isEqualTo("ACTIVE");

        registry.upsertInstruction(
                SCOPE, instruction("operator:refund-v3", "维持取消费", "UPHELD"));
        assertThat(BusinessGoldenContractGuard.isCurrent(states, SCOPE, row)).isFalse();
    }

    @Test
    void keepsApprovalAcrossSolutionRevisionButRejectsChangedSolutionBusinessDefinition() {
        Map<String, Object> proposed = golden.propose(proposal(), agent());
        String caseSetRef = proposed.get("caseSetRef").toString();
        Map<String, Object> review = reviews.oracleReview(caseSetRef, "g1", 1, reviewer());
        JsonNode row = reviews.approveOracle(caseSetRef, "g1", 1,
                review.get("proposalFingerprint").toString(), reviewer()).data().at("/rows/0");

        AgentTddStoredAsset current = states.find(
                SCOPE, SolutionEntityRegistry.SOLUTION, "sol:cancel").orElseThrow();
        AgentTddStoredAsset rebound = states.save(
                SCOPE, SolutionEntityRegistry.SOLUTION, "sol:cancel", current.data());
        assertThat(rebound.revision()).isGreaterThan(current.revision());
        assertThat(rebound.data().path("contractFingerprint").asText())
                .isEqualTo(current.data().path("contractFingerprint").asText());
        assertThat(BusinessGoldenContractGuard.isCurrent(states, SCOPE, row)).isTrue();

        registry.upsertSolution(SCOPE, solution("按修订政策处理取消费争议",
                List.of("ride.cancel.manual-review")), true);

        assertThat(BusinessGoldenContractGuard.isCurrent(states, SCOPE, row)).isFalse();
        assertThatThrownBy(() -> BusinessGoldenContractGuard.requireCurrent(states, SCOPE, row))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("GOLDEN_CASE_STALE"));
    }

    @Test
    void rejectsChangedReferencedContractEvenWhenAnotherEntityRetainsItsOldIdentity() {
        Map<String, Object> proposed = golden.propose(proposal(), agent());
        String caseSetRef = proposed.get("caseSetRef").toString();
        Map<String, Object> review = reviews.oracleReview(caseSetRef, "g1", 1, reviewer());
        JsonNode row = reviews.approveOracle(caseSetRef, "g1", 1,
                review.get("proposalFingerprint").toString(), reviewer()).data().at("/rows/0");
        JsonNode approvedCoordinate = row.path("businessContractVector").valueStream()
                .filter(value -> "FEATURE".equals(value.path("assetKind").asText())
                        && "responsibility.party".equals(value.path("assetRef").asText()))
                .findFirst().orElseThrow();

        ObjectNode substitute = mapper.createObjectNode();
        substitute.put("entityKind", "FEATURE");
        substitute.put("ref", "responsibility.party.substitute");
        substitute.set("contract", states.find(
                        SCOPE, SolutionEntityRegistry.FEATURE, "responsibility.party")
                .orElseThrow().data().path("contract").deepCopy());
        substitute.put("contractFingerprint",
                approvedCoordinate.path("contractFingerprint").asText());
        states.save(SCOPE, SolutionEntityRegistry.FEATURE,
                "responsibility.party.substitute", substitute);

        registry.upsertFeature(SCOPE, new FeatureContract(
                "responsibility.party", mapper.valueToTree(Map.of("type", "string")),
                FeatureContract.EvaluationKind.API, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("orderId", "string")), "", "", "", "新的取消责任方"));

        assertThat(BusinessGoldenContractGuard.isCurrent(states, SCOPE, row)).isFalse();
    }

    private ObjectNode proposal() {
        ObjectNode proposal = mapper.createObjectNode();
        proposal.put("journeyRef", "journey:cancel");
        proposal.put("solutionRef", "sol:cancel");
        proposal.put("idempotencyKey", "golden-original-business-case");
        proposal.set("cases", mapper.valueToTree(List.of(Map.of(
                "caseId", "g1",
                "businessIntent", "乘客无责时免除取消费",
                "givenFacts", List.of(
                        Map.of("factName", "取消责任方", "value", "none"),
                        Map.of("factName", "争议订单", "value", "O-1")),
                "dependencyAssumptions", List.of(Map.of(
                        "capabilityName", "维持取消费", "outcome", "SUCCEEDS_WITHOUT_EFFECT")),
                "expectedOutcome", Map.of(
                        "result", Map.of("decision", "WAIVED"), "reasoningClass", "责任不在乘客"),
                "oracleOwner", "cx-policy"))));
        return proposal;
    }

    private FeatureContract feature(String ref, String semantics, String binding) {
        String semanticKey = "responsibility.party".equals(ref)
                ? "ride.cancel.party" : "ride.cancel.order";
        BusinessFactSemanticContract definition = new BusinessFactSemanticContract(
                BusinessFactSemanticContract.SCHEMA_VERSION, semanticKey, semantics,
                "ride-cancellation", "ride-order", mapper.createArrayNode(),
                mapper.valueToTree(Map.of("type", "string")), "CANCELLATION_OCCURRED_AT",
                "REQUIRE_HUMAN_REVIEW", "PLATFORM", "order-system",
                mapper.valueToTree(Map.of("mode", "AS_OF_EVENT")), "READ", "ACTIVE");
        return new FeatureContract(ref, mapper.valueToTree(Map.of("type", "string")),
                FeatureContract.EvaluationKind.API, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("orderId", "string")), binding, "", "", semantics,
                definition);
    }

    private InstructionContract instruction(String binding, String semantics, String decision) {
        JsonNode output = mapper.valueToTree(Map.of(
                        "result", Map.of("type", Map.of("fields", Map.of(
                                "decision", Map.of("enum", List.of(decision))))),
                        "reasoning", "required"));
        BusinessInstructionSemanticContract definition = new BusinessInstructionSemanticContract(
                BusinessInstructionSemanticContract.SCHEMA_VERSION, "ride.cancel.waive",
                semantics, "ride-cancellation", "ride-order",
                List.of("ride.cancel.party", "ride.cancel.order"), output,
                "REQUIRED", "WRITE", "ESCALATE", "RECONCILED", "ACTIVE");
        return new InstructionContract("ins:refund", mapper.valueToTree(Map.of("orderId", "string")),
                output,
                InstructionContract.Effect.WRITE, binding,
                new InstructionContract.WriteGovernance("refund", "orderId", "recon:refund"),
                semantics, definition);
    }

    private ScenarioContract scenario() {
        return new ScenarioContract("scn:cancel", List.of("party"), ScenarioContract.HitPolicy.UNIQUE,
                List.of(new ScenarioContract.Rule("R1",
                        mapper.valueToTree(Map.of("party", Map.of("eq", "none"))),
                        new ScenarioContract.Outlet(ScenarioContract.OutletKind.INSTRUCTION,
                                "ins:refund", Map.of("orderId", "orderId"), ""))),
                new ScenarioContract.Outlet(
                        ScenarioContract.OutletKind.TERMINAL, "", Map.of(), "REVIEW"));
    }

    private SolutionContract solution(String intent, List<String> dispositionSemanticKeys) {
        BusinessSolutionSemanticContract definition = new BusinessSolutionSemanticContract(
                BusinessSolutionSemanticContract.SCHEMA_VERSION,
                "ride.cancel.dispute.solution", intent, "ride-cancellation", "ride-order",
                "CANCELLATION_FEE_DISPUTE",
                List.of("ride.cancel.party", "ride.cancel.order"),
                "ride.cancel.decision", dispositionSemanticKeys,
                "AGENT_ASSISTED", "ACTIVE");
        return new SolutionContract(
                "sol:cancel", "处理取消费争议",
                Map.of("party", "responsibility.party", "orderId", "dispute.orderSelected"),
                "scn:cancel", List.of("ins:refund"), "caseSet:cancel", definition);
    }

    private static IntegrationRequestContext agent() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                "WORKLOAD", "codex", "INTERNAL", "AGENT_TDD_AUTHORING", "corr-agent");
    }

    private static IntegrationRequestContext reviewer() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                "HUMAN", "reviewer", "INTERNAL", "AGENT_TDD_GOVERNANCE", "corr-review");
    }

    private static final class TestMaterialStore extends BusinessGoldenMaterialStore {
        private final ObjectMapper mapper;
        private final Map<String, JsonNode> payloads = new LinkedHashMap<>();

        private TestMaterialStore(ObjectMapper mapper) {
            super((com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialService) null,
                    mapper);
            this.mapper = mapper;
        }

        @Override
        public JsonNode write(String solutionRef, long solutionRevision, String solutionFingerprint,
                              String caseId, String goldenFingerprint, String proposalFingerprint,
                              JsonNode payload, IntegrationRequestContext caller) {
            payloads.put(goldenFingerprint, payload.deepCopy());
            return mapper.valueToTree(Map.of("fingerprint", goldenFingerprint, "revision", 1));
        }

        @Override
        public JsonNode read(JsonNode receiptNode, IntegrationRequestContext caller) {
            return payloads.get(receiptNode.path("fingerprint").asText()).deepCopy();
        }

        @Override
        public JsonNode renew(JsonNode receiptNode, IntegrationRequestContext caller) {
            ObjectNode successor = (ObjectNode) receiptNode.deepCopy();
            successor.put("revision", receiptNode.path("revision").asLong() + 1);
            return successor;
        }

        private JsonNode onlyPayload() {
            return payloads.values().iterator().next().deepCopy();
        }
    }
}
