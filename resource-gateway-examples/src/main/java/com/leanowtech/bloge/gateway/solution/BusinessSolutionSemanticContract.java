package com.leanowtech.bloge.gateway.solution;

import java.util.List;
import java.util.Locale;

/**
 * Implementation-independent business identity for one composed Solution.
 *
 * @param schemaVersion semantic contract schema
 * @param semanticKey governed business solution identity
 * @param intent business objective fulfilled by the solution
 * @param domain bounded business domain
 * @param businessObject primary subject of the solution
 * @param problemClass normalized business problem class
 * @param requiredFactKeys governed fact keys collected before decision
 * @param scenarioSemanticKey governed root decision identity
 * @param dispositionSemanticKeys governed business dispositions reachable from the solution
 * @param runtimeUse allowed business runtime use
 * @param lifecycle semantic-key lifecycle
 */
public record BusinessSolutionSemanticContract(
        String schemaVersion,
        String semanticKey,
        String intent,
        String domain,
        String businessObject,
        String problemClass,
        List<String> requiredFactKeys,
        String scenarioSemanticKey,
        List<String> dispositionSemanticKeys,
        String runtimeUse,
        String lifecycle
) {
    public static final String SCHEMA_VERSION = "rg.businessSolutionSemanticContract.v1";

    /** Normalizes labels, freezes keys and rejects incomplete new definitions. */
    public BusinessSolutionSemanticContract {
        schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION : normalized(schemaVersion);
        semanticKey = normalized(semanticKey);
        intent = normalized(intent);
        domain = normalized(domain);
        businessObject = normalized(businessObject);
        problemClass = upper(problemClass);
        requiredFactKeys = normalizedList(requiredFactKeys);
        scenarioSemanticKey = normalized(scenarioSemanticKey);
        dispositionSemanticKeys = normalizedList(dispositionSemanticKeys);
        runtimeUse = upper(runtimeUse);
        lifecycle = upper(lifecycle).isBlank() ? "PROPOSED" : upper(lifecycle);
        if (!SCHEMA_VERSION.equals(schemaVersion) || semanticKey.isBlank() || intent.isBlank()
                || domain.isBlank() || businessObject.isBlank() || problemClass.isBlank()
                || requiredFactKeys.isEmpty() || scenarioSemanticKey.isBlank()
                || dispositionSemanticKeys.isEmpty() || runtimeUse.isBlank()
                || !java.util.Set.of("PROPOSED", "ACTIVE", "DEPRECATED").contains(lifecycle)) {
            throw new IllegalArgumentException("Business solution semantic contract is incomplete");
        }
    }

    /** Creates the UNKNOWN compatibility projection used only for contracts written before v1.4.6. */
    public static BusinessSolutionSemanticContract legacy(String ref, List<String> facts) {
        return new BusinessSolutionSemanticContract(SCHEMA_VERSION, "legacy:" + normalized(ref), ref,
                "UNKNOWN", "UNKNOWN", "UNKNOWN", facts == null || facts.isEmpty()
                ? List.of("UNKNOWN") : facts, "UNKNOWN", List.of("UNKNOWN"), "UNKNOWN", "PROPOSED");
    }

    /** @return whether this compatibility projection is ineligible for exact reuse */
    public boolean incompleteLegacyProjection() {
        return semanticKey.startsWith("legacy:") || domain.equals("UNKNOWN")
                || businessObject.equals("UNKNOWN") || problemClass.equals("UNKNOWN");
    }

    private static List<String> normalizedList(List<String> values) {
        return values == null ? List.of() : values.stream().map(BusinessSolutionSemanticContract::normalized)
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    private static String normalized(String value) { return value == null ? "" : value.trim(); }
    private static String upper(String value) { return normalized(value).toUpperCase(Locale.ROOT); }
}
