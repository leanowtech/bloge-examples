package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.Locale;
import java.util.Map;

/**
 * Contract for one observable business action that always explains its result.
 *
 * <p>An instruction declares inputs, a structured {@code result}, and mandatory
 * {@code reasoning}. A write instruction without a {@code bindingRef} is intentionally
 * design-only: it can be stubbed during zero-egress simulation and must be handed to engineering
 * before governed execution. Write governance is contract material because reconciliation is part
 * of the business promise rather than an implementation detail.</p>
 *
 * @param instructionRef stable instruction reference
 * @param inputs declared feature-value input schema
 * @param output structured {@code result} plus mandatory {@code reasoning}
 * @param effect read or write effect classification
 * @param bindingRef executable implementation binding; blank is allowed only as a design state
 * @param writeGovernance downstream reconciliation contract for writes
 * @param businessSemantics human-readable disposition shown on review surfaces
 * @param businessDefinition structured, implementation-independent disposition identity
 * @param display independently revised business discovery and presentation material
 */
public record InstructionContract(
        String instructionRef,
        JsonNode inputs,
        JsonNode output,
        Effect effect,
        String bindingRef,
        WriteGovernance writeGovernance,
        String businessSemantics,
        BusinessInstructionSemanticContract businessDefinition,
        @com.fasterxml.jackson.annotation.JsonIgnore BusinessCapabilityDisplay display
) {
    /** Effect boundary used by simulation and governed runtime dispatch. */
    public enum Effect { READ, WRITE }

    /**
     * Required reconciliation coordinates for a write instruction.
     *
     * @param downstreamSystem business system expected to change
     * @param reconciliationKey input field used to read the observed effect back
     * @param reconciliationAdapterRef registered adapter that performs the read-back
     */
    public record WriteGovernance(
            String downstreamSystem,
            String reconciliationKey,
            String reconciliationAdapterRef
    ) {
        /** Normalizes and validates all write governance coordinates. */
        public WriteGovernance {
            downstreamSystem = normalized(downstreamSystem);
            reconciliationKey = normalized(reconciliationKey);
            reconciliationAdapterRef = normalized(reconciliationAdapterRef);
            if (downstreamSystem.isBlank() || reconciliationKey.isBlank()
                    || reconciliationAdapterRef.isBlank()) {
                throw new IllegalArgumentException("Write governance is incomplete");
            }
        }
    }

    /** Freezes schemas and enforces result-plus-reasoning and effect-specific invariants. */
    public InstructionContract {
        instructionRef = normalized(instructionRef);
        inputs = inputs == null || inputs.isMissingNode()
                ? JsonNodeFactory.instance.objectNode() : inputs.deepCopy();
        output = output == null ? null : output.deepCopy();
        bindingRef = normalized(bindingRef);
        businessSemantics = normalized(businessSemantics);
        if (businessSemantics.isBlank()) businessSemantics = instructionRef;
        if (instructionRef.isBlank() || !inputs.isObject() || output == null || !output.isObject()
                || output.path("result").isMissingNode()
                || !"required".equalsIgnoreCase(output.path("reasoning").asText())
                || effect == null) {
            throw new IllegalArgumentException("Instruction contract is incomplete");
        }
        if (effect == Effect.WRITE && writeGovernance == null) {
            throw new IllegalArgumentException("WRITE instruction requires write governance");
        }
        if (effect == Effect.READ && writeGovernance != null) {
            throw new IllegalArgumentException("READ instruction cannot declare write governance");
        }
        businessDefinition = businessDefinition == null
                ? BusinessInstructionSemanticContract.legacy(instructionRef, output, effect)
                : businessDefinition;
        display = display == null
                ? BusinessCapabilityDisplay.legacy(businessSemantics, businessDefinition.intent())
                : display;
    }

    /** Preserves v1.4.6 callers while deriving a compatibility display. */
    public InstructionContract(String instructionRef, JsonNode inputs, JsonNode output, Effect effect,
                               String bindingRef, WriteGovernance writeGovernance,
                               String businessSemantics,
                               BusinessInstructionSemanticContract businessDefinition) {
        this(instructionRef, inputs, output, effect, bindingRef, writeGovernance,
                businessSemantics, businessDefinition, null);
    }

    /** Preserves contracts authored before structured Instruction business semantics. */
    public InstructionContract(String instructionRef, JsonNode inputs, JsonNode output, Effect effect,
                               String bindingRef, WriteGovernance writeGovernance,
                               String businessSemantics) {
        this(instructionRef, inputs, output, effect, bindingRef, writeGovernance,
                businessSemantics, null, null);
    }

    /** Compatibility constructor for stored and authored contracts predating business labels. */
    public InstructionContract(String instructionRef, JsonNode inputs, JsonNode output, Effect effect,
                               String bindingRef, WriteGovernance writeGovernance) {
        this(instructionRef, inputs, output, effect, bindingRef, writeGovernance,
                instructionRef, null, null);
    }

    @Override
    public JsonNode inputs() {
        return inputs.deepCopy();
    }

    @Override
    public JsonNode output() {
        return output.deepCopy();
    }

    /** @return whether engineering still needs to bind a write implementation */
    public boolean speccing() {
        return effect == Effect.WRITE && bindingRef.isBlank();
    }

    /** @return whether the output contract requires an explanation alongside the result */
    public boolean reasoningRequired() {
        return true;
    }

    /**
     * Returns implementation-independent material used for GOLDEN identity and drift detection.
     *
     * <p>The executable {@code bindingRef} is deliberately excluded so an implementation can be
     * supplied without silently changing the approved business contract.</p>
     */
    public Map<String, Object> contractIdentity() {
        java.util.LinkedHashMap<String, Object> identity = new java.util.LinkedHashMap<>();
        identity.put("instructionRef", instructionRef);
        identity.put("inputs", inputs);
        identity.put("output", output);
        identity.put("effect", effect.name());
        identity.put("businessSemantics", businessSemantics);
        identity.put("businessDefinition", businessDefinition);
        if (writeGovernance != null) identity.put("writeGovernance", writeGovernance);
        return Map.copyOf(identity);
    }

    /** @return executable Instruction identity excluding independently revised display material */
    public Map<String, Object> implementationIdentity() {
        java.util.LinkedHashMap<String, Object> identity = new java.util.LinkedHashMap<>();
        identity.put("instructionRef", instructionRef);
        identity.put("inputs", inputs);
        identity.put("output", output);
        identity.put("effect", effect);
        identity.put("bindingRef", bindingRef);
        identity.put("writeGovernance", writeGovernance);
        identity.put("businessSemantics", businessSemantics);
        identity.put("businessDefinition", businessDefinition);
        return java.util.Collections.unmodifiableMap(identity);
    }

    /** Returns the same Instruction with independently revised discovery material. */
    public InstructionContract withDisplay(BusinessCapabilityDisplay revisedDisplay) {
        return new InstructionContract(instructionRef, inputs, output, effect, bindingRef,
                writeGovernance, businessSemantics, businessDefinition, revisedDisplay);
    }

    /** Parses a case-insensitive enum value without accepting unknown spellings. */
    public static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return Enum.valueOf(type, normalized(value).toUpperCase(Locale.ROOT));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
