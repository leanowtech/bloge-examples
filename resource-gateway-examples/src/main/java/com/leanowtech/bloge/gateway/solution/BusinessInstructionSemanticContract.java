package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.List;
import java.util.Locale;

/**
 * Implementation-independent business identity for one observable Instruction.
 *
 * @param schemaVersion semantic contract schema
 * @param semanticKey governed business disposition identity
 * @param intent business outcome produced by the instruction
 * @param domain bounded business domain
 * @param businessObject subject being affected or reported
 * @param requiredFactKeys governed fact keys required by the action
 * @param resultDomain closed result shape and meanings
 * @param reasoningPolicy explanation requirement
 * @param effect READ or WRITE business effect
 * @param failurePolicy business behavior when the action cannot complete
 * @param writeGovernanceClass governance class for write reconciliation
 * @param lifecycle semantic-key lifecycle
 */
public record BusinessInstructionSemanticContract(
        String schemaVersion,
        String semanticKey,
        String intent,
        String domain,
        String businessObject,
        List<String> requiredFactKeys,
        JsonNode resultDomain,
        String reasoningPolicy,
        String effect,
        String failurePolicy,
        String writeGovernanceClass,
        String lifecycle
) {
    public static final String SCHEMA_VERSION = "rg.businessInstructionSemanticContract.v1";

    /** Normalizes labels, freezes the result domain and rejects incomplete new definitions. */
    public BusinessInstructionSemanticContract {
        schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION : normalized(schemaVersion);
        semanticKey = normalized(semanticKey);
        intent = normalized(intent);
        domain = normalized(domain);
        businessObject = normalized(businessObject);
        requiredFactKeys = normalizedList(requiredFactKeys);
        resultDomain = resultDomain == null ? JsonNodeFactory.instance.objectNode() : resultDomain.deepCopy();
        reasoningPolicy = upper(reasoningPolicy);
        effect = upper(effect);
        failurePolicy = upper(failurePolicy);
        writeGovernanceClass = upper(writeGovernanceClass);
        lifecycle = upper(lifecycle).isBlank() ? "PROPOSED" : upper(lifecycle);
        if (!SCHEMA_VERSION.equals(schemaVersion) || semanticKey.isBlank() || intent.isBlank()
                || domain.isBlank() || businessObject.isBlank() || requiredFactKeys.isEmpty()
                || !resultDomain.isObject() || resultDomain.isEmpty() || reasoningPolicy.isBlank()
                || !(effect.equals("READ") || effect.equals("WRITE")) || failurePolicy.isBlank()
                || (effect.equals("WRITE") && writeGovernanceClass.isBlank())
                || !java.util.Set.of("PROPOSED", "ACTIVE", "DEPRECATED").contains(lifecycle)) {
            throw new IllegalArgumentException("Business instruction semantic contract is incomplete");
        }
        if (effect.equals("READ") && writeGovernanceClass.isBlank()) writeGovernanceClass = "NONE";
    }

    /** Creates the UNKNOWN compatibility projection used only for contracts written before v1.4.6. */
    public static BusinessInstructionSemanticContract legacy(
            String ref, JsonNode output, InstructionContract.Effect effect) {
        return new BusinessInstructionSemanticContract(SCHEMA_VERSION, "legacy:" + normalized(ref), ref,
                "UNKNOWN", "UNKNOWN", List.of("UNKNOWN"), output == null
                ? JsonNodeFactory.instance.objectNode().put("type", "UNKNOWN") : output,
                "UNKNOWN", effect == null ? "READ" : effect.name(), "UNKNOWN",
                effect == InstructionContract.Effect.WRITE ? "UNKNOWN" : "NONE", "PROPOSED");
    }

    /** @return whether this compatibility projection is ineligible for exact reuse */
    public boolean incompleteLegacyProjection() {
        return semanticKey.startsWith("legacy:") || domain.equals("UNKNOWN")
                || businessObject.equals("UNKNOWN") || reasoningPolicy.equals("UNKNOWN");
    }

    @Override public JsonNode resultDomain() { return resultDomain.deepCopy(); }

    private static List<String> normalizedList(List<String> values) {
        return values == null ? List.of() : values.stream().map(BusinessInstructionSemanticContract::normalized)
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    private static String normalized(String value) { return value == null ? "" : value.trim(); }
    private static String upper(String value) { return normalized(value).toUpperCase(Locale.ROOT); }
}
