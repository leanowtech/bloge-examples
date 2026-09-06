package com.leanowtech.bloge.gateway.solution.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.journey.BusinessFixtureIndexService;
import com.leanowtech.bloge.gateway.solution.journey.BusinessGoldenReviewAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies authorized, audited review of protected Feature controlled suites. */
class FeatureControlledSuiteReviewServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
    private final BusinessFixtureIndexService index = mock(BusinessFixtureIndexService.class);
    private final FeatureControlledMaterialStore materials = mock(FeatureControlledMaterialStore.class);
    private final RecordingAudits audits = new RecordingAudits();
    private FeatureControlledSuiteReviewService service;
    private FeatureControlledSuiteDefinition definition;

    @BeforeEach
    void setUp() {
        definition = new FeatureControlledSuiteDefinition(
                "feature:party", "graph:party", 0, List.of(), List.of("node:policy"),
                List.of(new FeatureControlledSuiteDefinition.Case(
                        "F1", "订单归责为乘客", mapper.valueToTree(Map.of("orderId", "O-1")),
                        List.of(), mapper.valueToTree(Map.of("party", "passenger")),
                        List.of("node:policy"))));
        String definitionFingerprint = com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint
                .fromCanonicalValue(mapper,
                        mapper.convertValue(definition.protectedMaterial(), Object.class), 16 * 1024 * 1024);
        ObjectNode data = mapper.createObjectNode();
        data.put("status", "PASSED");
        data.put("caseCount", 1);
        data.put("coverageTargetCount", 1);
        data.put("definitionFingerprint", definitionFingerprint);
        data.put("evidenceFingerprint", "sha256:" + "e".repeat(64));
        data.putObject("materialReceipt").put("classification", "RESTRICTED");
        states.save(AgentTddMutationService.scopeKey(reviewer()),
                FeatureControlledSuiteService.FEATURE_CONTROLLED_SUITE, "feature:party", data);
        when(index.listForSolution(org.mockito.ArgumentMatchers.eq("solution:cancel"),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new BusinessFixtureIndexService.CapabilityFixtures(
                        "FEATURE", "feature:party", "取消责任方", List.of())));
        when(materials.read(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(definition);
        service = new FeatureControlledSuiteReviewService(states, index, materials, audits, mapper);
    }

    @Test
    void listsMetadataThenLoadsExactProtectedCasesAndAuditsBothReads() {
        var list = service.listForSolution("solution:cancel", reviewer());
        var material = service.readMaterial("solution:cancel", "feature:party", reviewer());

        assertThat(list).singleElement().satisfies(summary -> {
            assertThat(summary.status()).isEqualTo("PASSED");
            assertThat(summary.caseCount()).isEqualTo(1);
            assertThat(summary.materialViewable()).isTrue();
        });
        assertThat(material.cases()).singleElement().satisfies(testCase -> {
            assertThat(testCase.intent()).isEqualTo("订单归责为乘客");
            assertThat(testCase.givenInputs().path("orderId").asText()).isEqualTo("O-1");
        });
        assertThat(audits.events).extracting(BusinessGoldenReviewAuditRepository
                        .BusinessGoldenReviewAccess::action)
                .containsExactly("FEATURE_SUITE_LIST", "FEATURE_SUITE_MATERIAL_REVIEW");
    }

    @Test
    void deniesWorkloadAndInsufficientClearanceBeforeDecryptingMaterial() {
        assertThatThrownBy(() -> service.listForSolution("solution:cancel", workload()))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("FEATURE_SUITE_REVIEW_ROLE_FORBIDDEN"));
        assertThatThrownBy(() -> service.readMaterial(
                "solution:cancel", "feature:party", lowClearance()))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("FEATURE_SUITE_REVIEW_CLEARANCE_FORBIDDEN"));
        assertThat(audits.events).allSatisfy(event ->
                assertThat(event.outcome()).isNotEqualTo("ACCEPTED"));
    }

    private static IntegrationRequestContext reviewer() {
        return identity("HUMAN", "RESTRICTED");
    }

    private static IntegrationRequestContext lowClearance() {
        return identity("HUMAN", "INTERNAL");
    }

    private static IntegrationRequestContext workload() {
        return identity("WORKLOAD", "RESTRICTED");
    }

    private static IntegrationRequestContext identity(String actorType, String clearance) {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                actorType, "reviewer", "", "SOLUTION_GOLDEN_REVIEW", "corr-review",
                Set.of("solution-golden-reviewers"), clearance, "");
    }

    private static final class RecordingAudits implements BusinessGoldenReviewAuditRepository {
        private final List<BusinessGoldenReviewAccess> events = new ArrayList<>();

        @Override
        public BusinessGoldenReviewAccess append(BusinessGoldenReviewAccess event) {
            events.add(event);
            return event;
        }
    }
}
