package com.leanowtech.bloge.gateway.solution;

import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.spring.annotation.BlogeOperator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/** Effect-aware BLOGE operator that dispatches one canonical Instruction contract. */
@Component
@BlogeOperator(
        value = "instructionCall",
        description = "Dispatch one governed Instruction with result and reasoning",
        owner = "bloge-platform",
        tags = {"solution", "instruction"})
public final class InstructionCallOperator implements Operator<Map<String, Object>, Map<String, Object>> {
    /** Runtime registry name used by Solution lowering. */
    public static final String NAME = "instructionCall";

    private final SolutionEntityRegistry registry;
    private final InstructionDispatchChannel channel;

    /** Creates the Spring operator and fails closed when no governed channel is installed. */
    @Autowired
    public InstructionCallOperator(
            SolutionEntityRegistry registry, ObjectProvider<InstructionDispatchChannel> provider) {
        this(registry, provider.getIfAvailable(() -> (instruction, values, context) -> {
            throw new SolutionContractException(
                    "INSTRUCTION_BINDING_UNAVAILABLE", "Instruction execution is unavailable.");
        }));
    }

    /** Creates a focused operator with an explicit dispatch channel. */
    public InstructionCallOperator(
            SolutionEntityRegistry registry, InstructionDispatchChannel channel) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    /** Stubs writes in simulation and delegates only an authorized executable path. */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> input, OperatorContext context) throws Exception {
        SolutionExecutionAuthority authority = SolutionExecutionAuthority.require(context.graphContext());
        String instructionRef = Objects.toString(
                input == null ? null : input.get("instructionRef"), "").trim();
        if (instructionRef.isBlank()) throw new SolutionContractException(
                "INSTRUCTION_INPUT_INVALID", "Instruction reference is required.");
        InstructionContract instruction;
        try {
            instruction = registry.requireInstruction(authority.scopeKey(), instructionRef);
        } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            throw new SolutionContractException(
                    "SCENARIO_OUTLET_UNRESOLVED", "An Instruction outlet is unresolved.");
        }
        Map<String, Object> values = input != null && input.get("values") instanceof Map<?, ?> raw
                ? raw.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> Objects.toString(entry.getKey()), Map.Entry::getValue))
                : Map.of();
        if (instruction.effect() == InstructionContract.Effect.WRITE
                && authority.mode() == SolutionExecutionAuthority.Mode.SIMULATE) {
            return Map.of(
                    "result", Map.of("status", "STUBBED"),
                    "reasoning", "SIMULATED_WRITE_STUB");
        }
        if (instruction.effect() == InstructionContract.Effect.WRITE
                && authority.mode() != SolutionExecutionAuthority.Mode.WRITE_EXEC) {
            throw new SolutionContractException(
                    "WRITE_EFFECT_NOT_ALLOWED", "WRITE instruction requires controlled execution.");
        }
        Map<String, Object> output = channel.execute(instruction, values, context);
        if (output == null || !output.containsKey("result")
                || Objects.toString(output.get("reasoning"), "").isBlank()) {
            throw new SolutionContractException(
                    "INSTRUCTION_OUTPUT_INVALID", "Instruction output requires result and reasoning.");
        }
        return Map.copyOf(output);
    }

    @Override
    public Idempotency idempotency() {
        return Idempotency.UNKNOWN;
    }

    @Override
    public SideEffectType sideEffectType() {
        return SideEffectType.MIXED;
    }
}
