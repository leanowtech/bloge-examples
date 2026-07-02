package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Faceted count summary for the visual operator catalog.
 *
 * @param total total matching operators
 * @param sourceKinds count by source kind
 * @param loweringModes count by lowering mode
 * @param capabilities count by runtime/governance capability facet
 */
public record OperatorCatalogFacets(
        int total,
        Map<String, Integer> sourceKinds,
        Map<String, Integer> loweringModes,
        Map<String, Integer> capabilities
) {
    /**
     * Creates a facet summary.
     */
    public OperatorCatalogFacets {
        sourceKinds = sourceKinds == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(sourceKinds));
        loweringModes = loweringModes == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(loweringModes));
        capabilities = capabilities == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(capabilities));
    }

    /**
     * Builds a summary from matching operators.
     *
     * @param operators matching operators
     * @return computed facets
     */
    public static OperatorCatalogFacets from(List<OperatorDefinition> operators) {
        List<OperatorDefinition> safeOperators = operators == null ? List.of() : operators.stream()
                .filter(operator -> operator != null)
                .toList();
        Map<String, Integer> sourceKinds = new TreeMap<>();
        Map<String, Integer> loweringModes = new TreeMap<>();
        Map<String, Integer> capabilities = new TreeMap<>();
        for (OperatorDefinition operator : safeOperators) {
            increment(sourceKinds, normalizeFacetValue(operator.source().kind()));
            increment(loweringModes, normalizeFacetValue(operator.lowering().mode()));
            for (String capability : capabilityValues(operator)) {
                increment(capabilities, capability);
            }
        }
        return new OperatorCatalogFacets(
                safeOperators.size(),
                new LinkedHashMap<>(sourceKinds),
                new LinkedHashMap<>(loweringModes),
                new LinkedHashMap<>(capabilities)
        );
    }

    static List<String> capabilityValues(OperatorDefinition operator) {
        if (operator == null) {
            return List.of();
        }
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        String sourceKind = normalizeFacetValue(operator.source().kind());
        String loweringMode = normalizeFacetValue(operator.lowering().mode());
        boolean streaming = operator.capabilities().streaming()
                || "java-streaming-operator".equals(sourceKind);
        boolean durable = operator.capabilities().durable()
                || "java-suspendable-operator".equals(sourceKind);
        if ("design".equals(loweringMode)) {
            values.add("design-only");
        } else if (!streaming && !durable) {
            values.add("runtime-executable");
        }
        if (streaming) {
            values.add("streaming");
        }
        if (durable) {
            values.add("durable");
        }
        if ("java-suspendable-operator".equals(sourceKind)) {
            values.add("suspendable");
        }
        if (operator.capabilities().requiresSecrets()) {
            values.add("requires-secret");
        }
        String effect = operator.capabilities().effect();
        if (!"PURE".equals(effect)) {
            values.add("external-effect");
            values.add(normalizeFacetValue(effect));
        } else {
            values.add("pure");
        }
        String idempotency = operator.capabilities().idempotency();
        if ("NON_IDEMPOTENT".equals(idempotency)) {
            values.add("non-idempotent");
        } else if ("IDEMPOTENT".equals(idempotency) || "DETERMINISTIC".equals(idempotency)) {
            values.add("idempotent");
        }
        values.add(normalizeFacetValue(idempotency));
        return values.stream()
                .filter(value -> !value.isBlank())
                .toList();
    }

    static String normalizeFacetValue(String value) {
        return String.valueOf(value == null ? "" : value)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
    }

    private static void increment(Map<String, Integer> counts, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        counts.merge(value, 1, Integer::sum);
    }
}
