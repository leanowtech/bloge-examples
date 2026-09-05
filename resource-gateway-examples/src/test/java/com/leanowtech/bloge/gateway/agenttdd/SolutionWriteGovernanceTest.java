package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.ReconciliationAdapter;
import com.leanowtech.bloge.gateway.solution.ReconciliationAdapterRegistry;
import com.leanowtech.bloge.gateway.solution.ScenarioContract;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Proves design-only handoff, controlled WRITE reconciliation and immutable Solution gates. */
class SolutionWriteGovernanceTest {
    private static final String SCOPE = "tenant-a|org-a|project-a|test|sg";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
    private final SolutionEntityRegistry registry = new SolutionEntityRegistry(states, mapper);
    private final AtomicInteger writes = new AtomicInteger();

    @BeforeEach
    void defineBoundWriteSolutionAndGolden() {
        registry.upsertInstruction(SCOPE, new InstructionContract(
                "ins:refund", mapper.valueToTree(Map.of("orderId", "string")),
                mapper.valueToTree(Map.of(
                        "result", Map.of("type", Map.of("fields", Map.of(
                                "decision", Map.of("enum", List.of("WAIVED"))))),
                        "reasoning", "required")),
                InstructionContract.Effect.WRITE, "operator:refund",
                new InstructionContract.WriteGovernance(
                        "refund-service", "orderId", "recon:refund-v1")));
        registry.upsertScenario(SCOPE, new ScenarioContract(
                "scn:root", List.of("party"), ScenarioContract.HitPolicy.UNIQUE,
                List.of(new ScenarioContract.Rule("R1",
                        mapper.valueToTree(Map.of("party", Map.of("eq", "none"))),
                        new ScenarioContract.Outlet(ScenarioContract.OutletKind.INSTRUCTION,
                                "ins:refund", Map.of("orderId", "orderId"), ""))),
                new ScenarioContract.Outlet(
                        ScenarioContract.OutletKind.TERMINAL, "", Map.of(), "ESCALATE")));
        registry.upsertSolution(SCOPE, new SolutionContract(
                "sol:cancel", "Resolve cancellation dispute.",
                Map.of("party", "responsibility.party", "orderId", "dispute.orderSelected"),
                "scn:root", List.of("ins:refund"), "caseSet:cancel"), false);
        ObjectNode cases = mapper.createObjectNode();
        cases.put("caseSetRef", "caseSet:cancel");
        cases.put("toolRef", "sol:cancel");
        cases.set("rows", mapper.valueToTree(List.of(Map.of(
                "caseId", "g1", "category", "GOLDEN", "lifecycle", "ACTIVE",
                "qualityState", "DESIGNED_NOT_RUN", "oracleOwner", "cx-owner",
                "given", Map.of("party", "none", "orderId", "O-1"), "stubs", Map.of(),
                "expect", Map.of("result", Map.of("decision", "WAIVED"))))));
        states.save(SCOPE, AgentTddMutationService.CASE_SET, "caseSet:cancel", cases);
        new SolutionTestingService(states, registry, mapper,
                (instruction, values, context) -> {
                    throw new AssertionError("GREEN baseline must not execute WRITE");
                }).baseline(SCOPE, "sol:cancel", "caseSet:cancel", "GREEN");
    }

    @Test
    void controlledWriteExecutesOnceAndReconcilesBeforeReadinessCanPass() {
        ReconciliationAdapter adapter = adapter(Map.of("decision", "WAIVED"));
        SolutionWriteExecutionRunner runner = runner(adapter);
        SolutionGovernanceService governance = new SolutionGovernanceService(states, registry, mapper);

        Map<String, Object> before = governance.readiness("sol:cancel", readIdentity());
        Map<String, Object> first = runner.execute("sol:cancel", writeIdentity("test"));
        Map<String, Object> replay = runner.execute("sol:cancel", writeIdentity("test"));
        Map<String, Object> after = governance.readiness("sol:cancel", readIdentity());

        assertThat(before).extracting("publishable").isEqualTo(false);
        assertThat(((Map<?, ?>) before.get("gates")).get("writeReconciled")).isEqualTo(false);
        assertThat(first).containsEntry("status", "RECONCILED").containsEntry("writeCount", 1);
        assertThat(replay).isEqualTo(first);
        assertThat(writes).hasValue(1);
        Map<?, ?> gates = (Map<?, ?>) after.get("gates");
        assertThat(gates.get("logicGreen")).isEqualTo(true);
        assertThat(gates.get("implementationBound")).isEqualTo(true);
        assertThat(gates.get("writeReconciled")).isEqualTo(true);
        assertThat(gates.get("ownerSignoff")).isEqualTo(false);
    }

    @Test
    void commitRejectsAReceiptNotIssuedForTheCurrentSolutionSourceAndContext() {
        SolutionGovernanceService governance = new SolutionGovernanceService(states, registry, mapper);

        assertThatThrownBy(() -> governance.commit(
                "sol:cancel", "sha256:not-the-authoring-receipt", authorIdentity()))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("GATE_REJECTED");
    }

    @Test
    void refusesAgentOrProductionWriteAuthorityAndReportsMismatch() {
        SolutionWriteExecutionRunner runner = runner(adapter(Map.of("decision", "UPHELD")));

        assertThatThrownBy(() -> runner.execute("sol:cancel", authorIdentity()))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("FORBIDDEN_PURPOSE");
        assertThatThrownBy(() -> runner.execute("sol:cancel", writeIdentity("prod")))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("EGRESS_NOT_ALLOWED");
        assertThat(runner.execute("sol:cancel", writeIdentity("test")))
                .containsEntry("status", "MISMATCH");
    }

    @Test
    void signoffCannotBeReusedAfterSolutionRevisionChanges() {
        SolutionWriteExecutionRunner runner = runner(adapter(Map.of("decision", "WAIVED")));
        runner.execute("sol:cancel", writeIdentity("test"));
        SolutionGovernanceService governance = new SolutionGovernanceService(states, registry, mapper);
        Map<String, Object> proposal = governance.commit(
                "sol:cancel", registry.requireSolutionAuthoringReceipt(SCOPE, "sol:cancel"), authorIdentity());
        Map<String, Object> readiness = governance.readiness("sol:cancel", readIdentity());
        governance.approve("sol:cancel", "signoff:owner-1",
                ((Number) readiness.get("solutionRevision")).longValue(),
                readiness.get("goldenSetId").toString(),
                readiness.get("evidenceFingerprint").toString(),
                readiness.get("implementationFingerprint").toString(),
                proposal.get("proposalFingerprint").toString(), humanIdentity());

        Map<String, Object> publication = governance.publish(
                "sol:cancel", "signoff:owner-1", humanIdentity());
        assertThat(publication).containsKey("publicationId");
        SolutionGovernanceService.CurrentPublication current = governance.requireCurrentPublication(
                "sol:cancel", executeIdentity());
        assertThat(current.publicationId()).isEqualTo(publication.get("publicationId"));
        assertThat(current.runtimeSnapshot().scenarios()).containsKey("scn:root");
        assertThat(current.runtimeSnapshot().instructions()).containsKey("ins:refund");

        registry.upsertSolution(SCOPE, new SolutionContract(
                "sol:cancel", "Resolve cancellation dispute with an amended policy.",
                Map.of("party", "responsibility.party", "orderId", "dispute.orderSelected"),
                "scn:root", List.of("ins:refund"), "caseSet:cancel"), false);
        assertThatThrownBy(() -> governance.publish(
                "sol:cancel", "signoff:owner-1", humanIdentity()))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("GATE_REJECTED");
    }

    @Test
    void bindingDriftInvalidatesBothReconciliationAndOwnerSignoff() {
        runner(adapter(Map.of("decision", "WAIVED")))
                .execute("sol:cancel", writeIdentity("test"));
        SolutionGovernanceService governance = new SolutionGovernanceService(states, registry, mapper);
        Map<String, Object> proposal = governance.commit(
                "sol:cancel", registry.requireSolutionAuthoringReceipt(SCOPE, "sol:cancel"), authorIdentity());
        Map<String, Object> readyToReview = governance.readiness("sol:cancel", readIdentity());
        governance.approve("sol:cancel", "signoff:implementation-1",
                ((Number) readyToReview.get("solutionRevision")).longValue(),
                readyToReview.get("goldenSetId").toString(),
                readyToReview.get("evidenceFingerprint").toString(),
                readyToReview.get("implementationFingerprint").toString(),
                proposal.get("proposalFingerprint").toString(), humanIdentity());

        registry.upsertInstruction(SCOPE, new InstructionContract(
                "ins:refund", mapper.valueToTree(Map.of("orderId", "string")),
                mapper.valueToTree(Map.of(
                        "result", Map.of("type", Map.of("fields", Map.of(
                                "decision", Map.of("enum", List.of("WAIVED"))))),
                        "reasoning", "required")),
                InstructionContract.Effect.WRITE, "operator:refund-v2",
                new InstructionContract.WriteGovernance(
                        "refund-service", "orderId", "recon:refund-v1")));

        Map<String, Object> afterDrift = governance.readiness("sol:cancel", readIdentity());
        assertThat(afterDrift.get("publishable")).isEqualTo(false);
        assertThat(afterDrift.get("implementationFingerprint"))
                .isNotEqualTo(readyToReview.get("implementationFingerprint"));
        Map<?, ?> gates = (Map<?, ?>) afterDrift.get("gates");
        assertThat(gates.get("writeReconciled")).isEqualTo(false);
        assertThat(gates.get("ownerSignoff")).isEqualTo(false);
        assertThatThrownBy(() -> governance.publish(
                "sol:cancel", "signoff:implementation-1", humanIdentity()))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("GATE_REJECTED");
    }

    @Test
    void engineeringHandoffContainsOnlyDesignOnlyWriteContracts() {
        registry.upsertInstruction(SCOPE, new InstructionContract(
                "ins:refund", mapper.valueToTree(Map.of("orderId", "string")),
                mapper.valueToTree(Map.of("result", Map.of("type", "object"), "reasoning", "required")),
                InstructionContract.Effect.WRITE, "",
                new InstructionContract.WriteGovernance(
                        "refund-service", "orderId", "recon:refund-v1")));

        Map<String, Object> handoff = new EngineeringHandoffService(states, registry, mapper)
                .submit("sol:cancel", authorIdentity());

        assertThat(handoff).containsEntry("status", "OPEN");
        com.fasterxml.jackson.databind.JsonNode items =
                (com.fasterxml.jackson.databind.JsonNode) handoff.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.path(0).path("state").asText()).isEqualTo("DESIGN_ONLY");
        assertThat(items.path(0).path("effect").asText()).isEqualTo("WRITE");
        assertThat(items.path(0).path("reconciliationKey").asText()).isEqualTo("orderId");
        assertThat(writes).hasValue(0);
    }

    @Test
    void boardProjectsSolutionPerformanceAndAnExactPendingOwnerDecision() {
        runner(adapter(Map.of("decision", "WAIVED")))
                .execute("sol:cancel", writeIdentity("test"));
        SolutionGovernanceService governance = new SolutionGovernanceService(states, registry, mapper);
        governance.commit("sol:cancel",
                registry.requireSolutionAuthoringReceipt(SCOPE, "sol:cancel"), authorIdentity());
        GraphDraftRepository drafts = mock(GraphDraftRepository.class);
        when(drafts.all()).thenReturn(List.of());

        Map<String, Object> board = new AgentTddBoardService(
                drafts, states, mock(AgentTddWorkflowService.class), mapper).board(readIdentity());

        Map<?, ?> solution = (Map<?, ?>) ((List<?>) board.get("solutions")).getFirst();
        assertThat(solution.get("solutionRef")).isEqualTo("sol:cancel");
        assertThat(solution.get("problem")).isEqualTo("Resolve cancellation dispute.");
        assertThat(solution.get("performance").toString()).contains("totalInvocations=0");
        assertThat(solution.get("scenario").toString()).contains("party", "none", "ins:refund");
        assertThat(solution.get("instructions").toString())
                .contains("WRITE", "BOUND", "refund-service");
        assertThat(solution.get("writeReconciliation").toString()).contains("RECONCILED");
        Map<?, ?> review = (Map<?, ?>) ((List<?>) board.get("pendingReviews")).getFirst();
        assertThat(review.get("kind")).isEqualTo("SOLUTION_SIGNOFF");
        List<String> reviewKeys = review.keySet().stream().map(Object::toString).toList();
        assertThat(reviewKeys).contains(
                "solutionRevision", "goldenSetId", "evidenceFingerprint",
                "implementationFingerprint", "proposalFingerprint");
        assertThat(reviewKeys).doesNotContain("contract", "given", "expect");
    }

    private SolutionWriteExecutionRunner runner(ReconciliationAdapter adapter) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("adapter", adapter);
        ReconciliationAdapterRegistry adapters = new ReconciliationAdapterRegistry(
                beans.getBeanProvider(ReconciliationAdapter.class));
        return new SolutionWriteExecutionRunner(states, registry, adapters, mapper,
                (instruction, values, context) -> {
                    writes.incrementAndGet();
                    return Map.of("result", Map.of("decision", "WAIVED"), "reasoning", "rule R1");
                });
    }

    private static ReconciliationAdapter adapter(Map<String, Object> effect) {
        return new ReconciliationAdapter() {
            @Override public String adapterRef() { return "recon:refund-v1"; }
            @Override public String downstreamSystem() { return "refund-service"; }
            @Override public ObservedEffect observe(String reconciliationKey, com.fasterxml.jackson.databind.JsonNode inputs) {
                return new ObservedEffect(reconciliationKey, effect);
            }
        };
    }

    private static IntegrationRequestContext authorIdentity() {
        return identity("WORKLOAD", "agent-1", "test", "AGENT_TDD_AUTHORING");
    }

    private static IntegrationRequestContext humanIdentity() {
        return identity("HUMAN", "owner-1", "test", "AGENT_TDD_GOVERNANCE");
    }

    private static IntegrationRequestContext readIdentity() {
        return identity("WORKLOAD", "agent-1", "test", "AGENT_TDD_READ");
    }

    private static IntegrationRequestContext writeIdentity(String environment) {
        return identity("PLATFORM", "system:write-exec-runner", environment, "AGENT_TDD_WRITE_EXEC");
    }

    private static IntegrationRequestContext executeIdentity() {
        return identity("WORKLOAD", "runtime-agent", "test", "AGENT_TDD_EXECUTION");
    }

    private static IntegrationRequestContext identity(
            String actorType, String actorId, String environment, String purpose) {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", environment,
                "sg", actorType, actorId, "", purpose, "corr-1");
    }
}
