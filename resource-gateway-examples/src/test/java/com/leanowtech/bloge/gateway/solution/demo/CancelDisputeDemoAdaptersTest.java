package com.leanowtech.bloge.gateway.solution.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the executable local backends used by the cancellation-dispute product journey. */
class CancelDisputeDemoAdaptersTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CancelDisputeDemoLedger ledger = new CancelDisputeDemoLedger();

    @Test
    void evaluatesResponsibilityAndFreeWindowFromSeededOrders() {
        RideResponsibilityBackend party = new RideResponsibilityBackend(ledger, mapper);
        CancelWithinFreeBackend withinFree = new CancelWithinFreeBackend(ledger, mapper);

        assertThat(party.evaluate(feature("responsibility.party", FeatureContract.EvaluationKind.API,
                        "demo:ride-responsibility-v1", "string"),
                mapper.valueToTree(Map.of("orderId", "O-FREE-NONE")), identity()).asText())
                .isEqualTo("none");
        assertThat(withinFree.evaluate(feature("cancel.withinFree", FeatureContract.EvaluationKind.DAG,
                        "demo:cancel-within-free-v1", "boolean"),
                mapper.valueToTree(Map.of("orderId", "O-FREE-NONE")), identity()).asBoolean())
                .isTrue();
        assertThat(party.supports("demo:ride-responsibility-v1")).isTrue();
        assertThat(withinFree.supports("demo:cancel-within-free-v1")).isTrue();
    }

    @Test
    void writesRefundAndTicketThenReconcilesTheObservedEffect() throws Exception {
        CancelDisputeInstructionChannel channel = new CancelDisputeInstructionChannel(ledger);
        RefundReconciliationAdapter refunds = new RefundReconciliationAdapter(ledger);
        TicketReconciliationAdapter tickets = new TicketReconciliationAdapter(ledger);
        InstructionContract refund = instruction("ins:refund-waive-full", "demo:refund-waive-full-v1",
                "refund-service", "recon:refund-v1");
        InstructionContract escalate = instruction("ins:escalate-human-ticket",
                "demo:escalate-human-ticket-v1", "ticket-service", "recon:ticket-v1");

        Map<String, Object> refundResult = channel.execute(refund,
                Map.of("orderId", "O-FREE-NONE"), null);
        Map<String, Object> ticketResult = channel.execute(escalate,
                Map.of("orderId", "O-DRIVER"), null);

        assertThat(refundResult.toString()).contains("WAIVED", "业务规则批准全额免除");
        assertThat(ticketResult.toString()).contains("ESCALATED", "转人工复核");
        assertThat(refunds.observe("O-FREE-NONE", mapper.createObjectNode()).effect())
                .containsEntry("decision", "WAIVED");
        assertThat(tickets.observe("O-DRIVER", mapper.createObjectNode()).effect())
                .containsEntry("decision", "ESCALATED");
    }

    @Test
    void failsClosedForUnknownOrdersAndBindings() {
        RideResponsibilityBackend party = new RideResponsibilityBackend(ledger, mapper);
        CancelDisputeInstructionChannel channel = new CancelDisputeInstructionChannel(ledger);

        assertThatThrownBy(() -> party.evaluate(feature("responsibility.party",
                        FeatureContract.EvaluationKind.API, "demo:ride-responsibility-v1", "string"),
                mapper.valueToTree(Map.of("orderId", "UNKNOWN")), identity()))
                .isInstanceOf(RuntimeException.class).hasMessage("Demo order is unavailable.");
        assertThatThrownBy(() -> channel.execute(instruction("ins:refund", "unknown-binding",
                        "refund-service", "recon:refund-v1"), Map.of("orderId", "O-FREE-NONE"), null))
                .isInstanceOf(RuntimeException.class).hasMessage("Instruction execution is unavailable.");
    }

    private FeatureContract feature(String ref, FeatureContract.EvaluationKind kind,
                                    String evaluationRef, String type) {
        return new FeatureContract(ref, mapper.valueToTree(Map.of("type", type)), kind,
                FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("orderId", "string")), evaluationRef, "", "", ref);
    }

    private InstructionContract instruction(String ref, String binding, String downstream, String adapter) {
        return new InstructionContract(ref, mapper.valueToTree(Map.of("orderId", "string")),
                mapper.valueToTree(Map.of("result", Map.of("type", Map.of("fields", Map.of(
                        "decision", Map.of("enum", List.of("WAIVED", "ESCALATED"))))),
                        "reasoning", "required")), InstructionContract.Effect.WRITE, binding,
                new InstructionContract.WriteGovernance(downstream, "orderId", adapter));
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                "PLATFORM", "demo", "", "AGENT_TDD_FEATURE_ENG", "corr-1");
    }
}
