package com.leanowtech.bloge.gateway.solution.journey;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.InstructionDispatchChannel;
import com.leanowtech.bloge.gateway.solution.InstructionStubFactory;
import com.leanowtech.bloge.gateway.solution.SolutionContractException;

import java.util.Map;
import java.util.Objects;

/**
 * Case-scoped Instruction adapter that has no path to the governed runtime channel.
 *
 * <p>All values were checked against the frozen Instruction contract during compilation. Failure
 * assumptions produce a stable controlled business result and can therefore be compared with the
 * approved Oracle instead of aborting the complete baseline.</p>
 */
public final class ControlledInstructionAdapter implements InstructionDispatchChannel {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final JsonNode assumptions;
    private final ObjectMapper mapper;

    /** Creates an adapter over one immutable controlled plan. */
    public ControlledInstructionAdapter(JsonNode assumptions, ObjectMapper mapper) {
        if (assumptions == null || !assumptions.isObject()) {
            throw new IllegalArgumentException("Controlled assumptions are required");
        }
        this.assumptions = assumptions.deepCopy();
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Returns only compiled in-process outcomes and rejects any undeclared selected path. */
    @Override
    public Map<String, Object> execute(
            InstructionContract instruction, Map<String, Object> values, OperatorContext context) {
        JsonNode assumption = assumptions.path(instruction.instructionRef());
        if (!assumption.isObject()
                || !"INSTRUCTION".equals(assumption.path("assetKind").asText("INSTRUCTION"))) {
            throw required();
        }
        return switch (assumption.path("outcome").asText()) {
            case "RETURNS" -> mapper.convertValue(assumption.path("value"), OBJECT_MAP);
            case "SUCCEEDS_WITHOUT_EFFECT" -> InstructionStubFactory.from(instruction);
            case "UNAVAILABLE" -> failure(assumption, "CONTROLLED_DEPENDENCY_UNAVAILABLE");
            case "FAILS_WITHOUT_EFFECT" -> failure(
                    assumption, "CONTROLLED_DEPENDENCY_FAILED_WITHOUT_EFFECT");
            case "MUST_NOT_BE_USED" -> throw new SolutionContractException(
                    "CONTROLLED_DEPENDENCY_FORBIDDEN", "A forbidden Instruction path was selected.");
            default -> throw required();
        };
    }

    private static Map<String, Object> failure(JsonNode assumption, String reasoning) {
        String dependencyStatus = "UNAVAILABLE".equals(assumption.path("outcome").asText())
                ? "UNAVAILABLE" : "FAILED_WITHOUT_EFFECT";
        return Map.of("result", Map.of("dependencyStatus", dependencyStatus), "reasoning", reasoning);
    }

    private static SolutionContractException required() {
        return new SolutionContractException(
                "CONTROLLED_ASSUMPTION_REQUIRED", "A controlled Instruction assumption is required.");
    }
}
