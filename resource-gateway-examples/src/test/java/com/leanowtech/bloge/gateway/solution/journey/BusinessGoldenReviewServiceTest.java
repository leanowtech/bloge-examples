package com.leanowtech.bloge.gateway.solution.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the human-only, payload-audited projection of protected business GOLDEN assets. */
class BusinessGoldenReviewServiceTest {
    private static final String SCOPE = "tenant-a|org-a|project-a|test|sg";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
    private final TestMaterialStore materials = new TestMaterialStore(mapper);
    private final RecordingAuditRepository audits = new RecordingAuditRepository();
    private BusinessGoldenReviewService service;

    @BeforeEach
    void setUp() {
        ObjectNode contract = mapper.createObjectNode();
        contract.put("contractFingerprint", "sha256:" + "c".repeat(64));
        contract.putObject("contract").putObject("businessDefinition")
                .put("semanticKey", "ride.cancel.dispute.solution");
        states.save(SCOPE, SolutionEntityRegistry.SOLUTION, "sol:cancel", contract);

        ObjectNode caseSet = mapper.createObjectNode();
        caseSet.put("toolRef", "sol:cancel");
        caseSet.put("journeyRef", "journey:cancel");
        ObjectNode row = caseSet.putArray("rows").addObject();
        row.put("caseId", "g1");
        row.put("category", "GOLDEN");
        row.put("oracleOwner", "cx-owner");
        row.put("lifecycle", "ACTIVE");
        row.put("qualityState", "GREEN");
        row.put("factCount", 2);
        row.put("assumptionCount", 1);
        row.put("goldenCaseFingerprint", "sha256:" + "g".repeat(64));
        row.putArray("businessContractVector").addObject()
                .put("assetKind", "SOLUTION")
                .put("assetRef", "sol:cancel")
                .put("semanticKey", "ride.cancel.dispute.solution")
                .put("contractFingerprint", "sha256:" + "c".repeat(64));
        row.set("materialReceipt", mapper.valueToTree(Map.of(
                "fingerprint", "sha256:" + "g".repeat(64),
                "classification", "INTERNAL")));
        row.putObject("proposedOracle").put("status", "APPROVED");
        states.save(SCOPE, AgentTddMutationService.CASE_SET, "caseSet:journey:cancel", caseSet);
        materials.payload = mapper.valueToTree(Map.of(
                "caseId", "g1",
                "businessIntent", "乘客无责时免除取消费",
                "givenFacts", List.of(Map.of("factName", "取消责任方", "value", "none")),
                "dependencyAssumptions", List.of(Map.of(
                        "capabilityName", "维持取消费", "outcome", "SUCCEEDS_WITHOUT_EFFECT")),
                "expectedOutcome", Map.of(
                        "result", Map.of("decision", "WAIVED"), "reasoningClass", "责任不在乘客"),
                "oracleOwner", "cx-owner",
                "goldenCaseFingerprint", "sha256:" + "g".repeat(64)));
        service = new BusinessGoldenReviewService(states, materials, audits);
    }

    @Test
    void listsOnlySafeMetadataAndRecordsHumanOwnership() {
        Map<String, Object> result = service.list("sol:cancel", "journey:cancel", owner());

        assertThat(result).containsEntry("caseSetRef", "caseSet:journey:cancel")
                .containsEntry("approvalState", "APPROVED");
        JsonNode json = mapper.valueToTree(result);
        assertThat(json.at("/cases/0/materialViewable").asBoolean()).isTrue();
        assertThat(json.toString()).doesNotContain(
                "businessIntent", "givenFacts", "dependencyAssumptions", "expectedOutcome",
                "materialReceipt", "乘客", "WAIVED");
        assertThat(materials.reads).isZero();
        assertThat(audits.events).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("GOLDEN_SET_LIST");
            assertThat(event.outcome()).isEqualTo("ACCEPTED");
            assertThat(event.actorId()).isEqualTo("cx-owner");
        });
    }

    @Test
    void readsOnlyBusinessMaterialForAnAuthorizedReviewerAndAuditsTheAccess() {
        Map<String, Object> result = service.readMaterial(
                "sol:cancel", "journey:cancel", "g1", reviewer());

        assertThat(result.keySet()).containsExactlyInAnyOrder(
                "caseId", "businessIntent", "givenFacts", "dependencyAssumptions",
                "expectedOutcome", "oracleOwner");
        assertThat(result.toString()).contains("乘客无责", "WAIVED")
                .doesNotContain("goldenCaseFingerprint", "materialReceipt", "businessContractVector");
        assertThat(materials.reads).isOne();
        assertThat(audits.events).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("GOLDEN_MATERIAL_REVIEW");
            assertThat(event.outcome()).isEqualTo("ACCEPTED");
            assertThat(event.caseId()).isEqualTo("g1");
        });
    }

    @Test
    void deniesWorkloadsWrongPurposesOwnersAndInsufficientClearanceBeforeDecrypting() {
        List<IntegrationRequestContext> denied = List.of(
                identity("WORKLOAD", "cx-owner", "SOLUTION_GOLDEN_REVIEW",
                        Set.of(), "RESTRICTED"),
                identity("HUMAN", "cx-owner", "AGENT_TDD_GOVERNANCE",
                        Set.of(), "RESTRICTED"),
                identity("HUMAN", "stranger", "SOLUTION_GOLDEN_REVIEW",
                        Set.of(), "RESTRICTED"),
                identity("HUMAN", "reviewer", "SOLUTION_GOLDEN_REVIEW",
                        Set.of("solution-golden-reviewers"), "PUBLIC"));

        denied.forEach(identity -> assertThatThrownBy(() -> service.readMaterial(
                "sol:cancel", "journey:cancel", "g1", identity))
                .isInstanceOf(AgentTddToolException.class));

        assertThat(materials.reads).isZero();
        assertThat(audits.events).hasSize(4).allSatisfy(event ->
                assertThat(event.outcome()).isNotEqualTo("ACCEPTED"));
    }

    @Test
    void failsClosedWhenTheIndependentHumanAuditCannotCommit() {
        audits.failure = new IllegalStateException("database unavailable");

        assertThatThrownBy(() -> service.readMaterial(
                "sol:cancel", "journey:cancel", "g1", owner()))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("GOLDEN_REVIEW_AUDIT_UNAVAILABLE"));
        assertThat(materials.reads).isOne();
    }

    private static IntegrationRequestContext owner() {
        return identity("HUMAN", "cx-owner", "SOLUTION_GOLDEN_REVIEW", Set.of(), "INTERNAL");
    }

    private static IntegrationRequestContext reviewer() {
        return identity("HUMAN", "reviewer", "SOLUTION_GOLDEN_REVIEW",
                Set.of("solution-golden-reviewers"), "RESTRICTED");
    }

    private static IntegrationRequestContext identity(String actorType,
                                                      String actorId,
                                                      String purpose,
                                                      Set<String> groups,
                                                      String clearance) {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                actorType, actorId, "", purpose, "corr-review", groups, clearance, "");
    }

    private static final class TestMaterialStore extends BusinessGoldenMaterialStore {
        private JsonNode payload;
        private int reads;

        private TestMaterialStore(ObjectMapper mapper) {
            super((com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialService) null,
                    mapper);
        }

        @Override
        public JsonNode read(JsonNode receiptNode, IntegrationRequestContext caller) {
            reads++;
            return payload.deepCopy();
        }
    }

    private static final class RecordingAuditRepository
            implements BusinessGoldenReviewAuditRepository {
        private final List<BusinessGoldenReviewAccess> events = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public BusinessGoldenReviewAccess append(BusinessGoldenReviewAccess event) {
            if (failure != null) throw failure;
            events.add(event);
            return event;
        }
    }
}
