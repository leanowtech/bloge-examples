package com.leanowtech.bloge.gateway.solution.demo;

import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.InstructionDispatchChannel;
import com.leanowtech.bloge.gateway.solution.SolutionContractException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/** Executes the three bounded dispositions used by the local cancellation-dispute journey. */
@Component
@ConditionalOnProperty(prefix = "gateway.agent-tdd.cancel-dispute-demo", name = "enabled",
        havingValue = "true")
public final class CancelDisputeInstructionChannel implements InstructionDispatchChannel {
    private final CancelDisputeDemoLedger ledger;

    /** Creates the dispatch channel over the local refund and ticket systems. */
    public CancelDisputeInstructionChannel(CancelDisputeDemoLedger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    /** Applies only recognized versioned bindings and returns result plus business reasoning. */
    @Override
    public Map<String, Object> execute(
            InstructionContract instruction, Map<String, Object> values, OperatorContext context) {
        String orderId = Objects.toString(values.get("orderId"), "");
        return switch (instruction.bindingRef()) {
            case "demo:refund-waive-full-v1" -> Map.of(
                    "result", ledger.waive(orderId), "reasoning", "业务规则批准全额免除");
            case "demo:escalate-human-ticket-v1" -> Map.of(
                    "result", ledger.escalate(orderId), "reasoning", "规则要求转人工复核");
            case "demo:uphold-v1" -> Map.of(
                    "result", Map.of("decision", "UPHELD"), "reasoning", "规则要求维持原收费");
            default -> throw new SolutionContractException(
                    "INSTRUCTION_BINDING_UNAVAILABLE", "Instruction execution is unavailable.");
        };
    }
}
