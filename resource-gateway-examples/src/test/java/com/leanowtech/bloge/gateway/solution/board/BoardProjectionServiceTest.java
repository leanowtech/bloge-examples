package com.leanowtech.bloge.gateway.solution.board;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.SolutionTestingService;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
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

/** Verifies the no-DSL business review projection for one Solution. */
class BoardProjectionServiceTest {
    private static final String SCOPE = "tenant-a|org-a|project-a|test|sg";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
    private final SolutionEntityRegistry registry = new SolutionEntityRegistry(states, mapper);

    @BeforeEach
    void storeBusinessDesignAndRedEvidence() {
        registry.upsertFeature(SCOPE, new FeatureContract(
                "responsibility.party", mapper.valueToTree(Map.of(
                        "type", Map.of("enum", List.of("passenger", "driver", "none")))),
                FeatureContract.EvaluationKind.API, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("orderId", "string")), "resource:party", "", "",
                "取消费责任方"));
        registry.upsertFeature(SCOPE, new FeatureContract(
                "dispute.orderSelected", mapper.valueToTree(Map.of("type", "string")),
                FeatureContract.EvaluationKind.USER_COMPONENT, FeatureContract.Determinism.INTERACTIVE,
                mapper.createObjectNode(), "", "order-picker", "", "用户选择的争议订单"));
        registry.upsertInstruction(SCOPE, new InstructionContract(
                "ins:refund-waive-full", mapper.valueToTree(Map.of("orderId", "string")),
                mapper.valueToTree(Map.of("result", Map.of("type", Map.of("fields", Map.of(
                        "decision", Map.of("enum", List.of("WAIVED"))))), "reasoning", "required")),
                InstructionContract.Effect.WRITE, "",
                new InstructionContract.WriteGovernance("refund-service", "orderId", "recon:refund"),
                "全额免除取消费"));
        registry.upsertScenario(SCOPE, new ScenarioContract(
                "scn:cancel", List.of("party"), ScenarioContract.HitPolicy.UNIQUE,
                List.of(new ScenarioContract.Rule("R1",
                        mapper.valueToTree(Map.of("party", Map.of("eq", "none"))),
                        new ScenarioContract.Outlet(ScenarioContract.OutletKind.INSTRUCTION,
                                "ins:refund-waive-full", Map.of("orderId", "orderId"), ""))),
                new ScenarioContract.Outlet(ScenarioContract.OutletKind.TERMINAL,
                        "", Map.of(), "转人工复核")));
        registry.upsertSolution(SCOPE, new SolutionContract(
                "sol:cancel-dispute", "一致处理取消费争议",
                Map.of("party", "responsibility.party", "orderId", "dispute.orderSelected"),
                "scn:cancel", List.of("ins:refund-waive-full"), "caseSet:cancel"), true);

        ObjectNode cases = mapper.createObjectNode().put("toolRef", "sol:cancel-dispute");
        cases.putArray("rows").addObject().put("caseId", "G1")
                .put("category", "GOLDEN").put("lifecycle", "ACTIVE")
                .set("given", mapper.valueToTree(Map.of("party", "none", "orderId", "O-1")));
        cases.withArray("rows").get(0).withObject("expect")
                .set("result", mapper.valueToTree(Map.of("decision", "WAIVED")));
        states.save(SCOPE, AgentTddMutationService.CASE_SET, "caseSet:cancel", cases);

        ObjectNode evidence = mapper.createObjectNode().put("solutionRef", "sol:cancel-dispute")
                .put("caseSetRef", "caseSet:cancel").put("side", "RED")
                .put("goldenSetId", "sha256:golden");
        evidence.set("cases", mapper.valueToTree(List.of(Map.of(
                "caseId", "G1", "verdict", "RED_FAIL",
                "instructionRef", "ins:refund-waive-full", "rulePath", List.of("R1")))));
        evidence.set("businessBacklog", mapper.valueToTree(List.of(Map.of(
                "caseId", "G1", "reason", "RED_FAIL", "owner", "业务负责人"))));
        states.save(SCOPE, SolutionTestingService.SOLUTION_EVIDENCE, "sol:cancel-dispute", evidence);
    }

    @Test
    void projectsFiveBusinessPanelsWithoutDslOrGraphMaterial() throws Exception {
        BoardProjectionService.BoardView view = new BoardProjectionService(states, mapper)
                .project("sol:cancel-dispute", reviewer());

        assertThat(view.solutionName()).isEqualTo("sol:cancel-dispute");
        assertThat(view.problem()).isEqualTo("一致处理取消费争议");
        assertThat(view.ruleMatrix().conditions()).containsExactly("取消费责任方");
        assertThat(view.ruleMatrix().rules()).singleElement().satisfies(rule -> {
            assertThat(rule.ruleId()).isEqualTo("R1");
            assertThat(rule.cells()).containsEntry("取消费责任方", "等于 none");
            assertThat(rule.disposition()).isEqualTo("全额免除取消费");
        });
        assertThat(view.dispositions()).singleElement().satisfies(card -> {
            assertThat(card.instructionName()).isEqualTo("全额免除取消费");
            assertThat(card.effectText()).isEqualTo("写入业务系统");
            assertThat(card.resultFields()).extracting(BoardProjectionService.ResultField::name)
                    .containsExactly("decision");
            assertThat(card.reconciliation().downstream()).isEqualTo("refund-service");
        });
        assertThat(view.featureCards()).hasSize(2).anySatisfy(card -> {
            if (card.featureName().equals("dispute.orderSelected")) {
                assertThat(card.sourceText()).isEqualTo("用户选择");
                assertThat(card.state()).isEqualTo("就绪");
                assertThat(card.tokenCapability()).isFalse();
            }
        });
        assertThat(view.redGreen().cases()).singleElement().satisfies(row -> {
            assertThat(row.caseId()).isEqualTo("G1");
            assertThat(row.expected()).containsEntry("decision", "WAIVED");
            assertThat(row.actual()).isEqualTo("refund waive full");
            assertThat(row.verdict()).isEqualTo("红");
        });
        assertThat(view.publishCard().publishable()).isFalse();
        String json = mapper.writeValueAsString(view).toLowerCase();
        assertThat(json).doesNotContain("dsl", "yaml", "graphdraft", "lowereddraft");
    }

    private static IntegrationRequestContext reviewer() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                "USER", "reviewer-1", "", "AGENT_TDD_GOVERNANCE", "corr-1");
    }
}
