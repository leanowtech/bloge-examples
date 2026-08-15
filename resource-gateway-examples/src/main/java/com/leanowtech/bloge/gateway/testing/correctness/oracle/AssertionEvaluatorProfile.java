package com.leanowtech.bloge.gateway.testing.correctness.oracle;

import java.util.List;

/** Exact evaluator version and capability set used for deterministic compilation. */
public record AssertionEvaluatorProfile(
        String evaluatorVersion,
        List<String> capabilities
) {
    public AssertionEvaluatorProfile {
        evaluatorVersion = required(evaluatorVersion, "evaluatorVersion");
        capabilities = capabilities == null ? List.of() : capabilities.stream()
                .map(value -> required(value, "capability").toUpperCase())
                .distinct()
                .sorted()
                .toList();
    }

    public boolean supports(String capability) {
        return capabilities.contains(required(capability, "capability").toUpperCase());
    }

    /** Capabilities implemented today by {@code TestAssertionEvaluator}. */
    public static AssertionEvaluatorProfile fixtureEvaluatorV1() {
        return new AssertionEvaluatorProfile(
                "bloge.fixtureAssertionEvaluator.v1",
                List.of(
                        "RUNTIME:NODE:STATUS",
                        "RUNTIME:OUTPUT:ABSENT",
                        "RUNTIME:OUTPUT:CONTAINS",
                        "RUNTIME:OUTPUT:EQUALS",
                        "RUNTIME:OUTPUT:EXISTS",
                        "RUNTIME:OUTPUT:SCHEMA"));
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
