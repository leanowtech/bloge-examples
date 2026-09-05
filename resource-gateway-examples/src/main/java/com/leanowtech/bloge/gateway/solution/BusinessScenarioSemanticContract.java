package com.leanowtech.bloge.gateway.solution;

import java.util.List;
import java.util.Locale;

/**
 * Implementation-independent business identity for one decision Scenario.
 *
 * @param schemaVersion semantic contract schema
 * @param semanticKey governed business decision identity
 * @param intent business question answered by the decision
 * @param domain bounded business domain
 * @param businessObject subject being decided
 * @param inputFactKeys governed fact keys consumed by the decision
 * @param decisionPolicy deterministic decision policy such as UNIQUE
 * @param outletSemanticKeys governed result or disposition keys selected by rules
 * @param otherwisePolicy business handling when no explicit rule matches
 * @param lifecycle semantic-key lifecycle
 */
public record BusinessScenarioSemanticContract(
        String schemaVersion,
        String semanticKey,
        String intent,
        String domain,
        String businessObject,
        List<String> inputFactKeys,
        String decisionPolicy,
        List<String> outletSemanticKeys,
        String otherwisePolicy,
        String lifecycle
) {
    public static final String SCHEMA_VERSION = "rg.businessScenarioSemanticContract.v1";

    /** Normalizes labels, freezes ordered keys and rejects incomplete new definitions. */
    public BusinessScenarioSemanticContract {
        schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION : normalized(schemaVersion);
        semanticKey = normalized(semanticKey);
        intent = normalized(intent);
        domain = normalized(domain);
        businessObject = normalized(businessObject);
        inputFactKeys = normalizedList(inputFactKeys);
        decisionPolicy = upper(decisionPolicy);
        outletSemanticKeys = normalizedList(outletSemanticKeys);
        otherwisePolicy = upper(otherwisePolicy);
        lifecycle = upper(lifecycle).isBlank() ? "PROPOSED" : upper(lifecycle);
        if (!SCHEMA_VERSION.equals(schemaVersion) || semanticKey.isBlank() || intent.isBlank()
                || domain.isBlank() || businessObject.isBlank() || inputFactKeys.isEmpty()
                || decisionPolicy.isBlank() || outletSemanticKeys.isEmpty() || otherwisePolicy.isBlank()
                || !java.util.Set.of("PROPOSED", "ACTIVE", "DEPRECATED").contains(lifecycle)) {
            throw new IllegalArgumentException("Business scenario semantic contract is incomplete");
        }
    }

    /** Creates the UNKNOWN compatibility projection used only for contracts written before v1.4.6. */
    public static BusinessScenarioSemanticContract legacy(String ref, List<String> inputs) {
        return new BusinessScenarioSemanticContract(SCHEMA_VERSION, "legacy:" + normalized(ref), ref,
                "UNKNOWN", "UNKNOWN", inputs == null || inputs.isEmpty() ? List.of("UNKNOWN") : inputs,
                "UNKNOWN", List.of("UNKNOWN"), "UNKNOWN", "PROPOSED");
    }

    /** @return whether this compatibility projection is ineligible for exact reuse */
    public boolean incompleteLegacyProjection() {
        return semanticKey.startsWith("legacy:") || domain.equals("UNKNOWN")
                || businessObject.equals("UNKNOWN") || decisionPolicy.equals("UNKNOWN");
    }

    private static List<String> normalizedList(List<String> values) {
        return values == null ? List.of() : values.stream().map(BusinessScenarioSemanticContract::normalized)
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    private static String normalized(String value) { return value == null ? "" : value.trim(); }
    private static String upper(String value) { return normalized(value).toUpperCase(Locale.ROOT); }
}
