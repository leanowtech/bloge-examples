package com.leanowtech.bloge.gateway.solution.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionContractException;

import java.util.Map;
import java.util.Objects;

/**
 * Consumes case-scoped Feature assumptions without holding a real evaluation backend.
 *
 * <p>RETURNS values have already been contract-checked by {@link BusinessFixtureCompiler} and are
 * copied into the Solution input. Dependency failures become an explicit stable business result
 * so a GOLDEN Oracle can approve that behavior. A forbidden Feature fails only when the
 * Solution declares and therefore touches that input.</p>
 */
public final class ControlledFeatureAdapter {
    private final ControlledTestEgressGuard egressGuard;

    /** Creates a Feature adapter inside the supplied deny-all boundary. */
    public ControlledFeatureAdapter(ControlledTestEgressGuard egressGuard) {
        this.egressGuard = Objects.requireNonNull(egressGuard, "egressGuard");
    }

    /** Resolves controlled Feature inputs or returns one stable controlled failure result. */
    public Resolution resolve(SolutionContract solution, JsonNode supplied, JsonNode assumptions) {
        Objects.requireNonNull(solution, "solution");
        if (supplied == null || !supplied.isObject() || assumptions == null || !assumptions.isObject()) {
            throw new SolutionContractException(
                    "CONTROLLED_ASSUMPTION_REQUIRED", "Controlled Feature inputs are incomplete.");
        }
        egressGuard.verifyBeforeCase();
        ObjectNode values = (ObjectNode) supplied.deepCopy();
        for (Map.Entry<String, String> input : solution.inputs().entrySet()) {
            JsonNode assumption = assumptions.path(input.getValue());
            if (!assumption.isObject() || !"FEATURE".equals(assumption.path("assetKind").asText())) {
                continue;
            }
            String alias = assumption.path("inputAlias").asText();
            if (!input.getKey().equals(alias)) throw required();
            switch (assumption.path("outcome").asText()) {
                case "RETURNS" -> values.set(alias, assumption.path("value").deepCopy());
                case "UNAVAILABLE" -> {
                    return Resolution.failure(values, "UNAVAILABLE",
                            "CONTROLLED_DEPENDENCY_UNAVAILABLE");
                }
                case "FAILS_WITHOUT_EFFECT" -> {
                    return Resolution.failure(values, "FAILED_WITHOUT_EFFECT",
                            "CONTROLLED_DEPENDENCY_FAILED_WITHOUT_EFFECT");
                }
                case "MUST_NOT_BE_USED" -> throw new SolutionContractException(
                        "CONTROLLED_DEPENDENCY_FORBIDDEN", "A forbidden Feature path was selected.");
                default -> throw required();
            }
        }
        return Resolution.values(values);
    }

    private static SolutionContractException required() {
        return new SolutionContractException(
                "CONTROLLED_ASSUMPTION_REQUIRED", "A controlled Feature assumption is required.");
    }

    /** Immutable controlled Feature result with either values or a business fallback. */
    public record Resolution(JsonNode values, Map<String, Object> failureResult, String reasoning) {
        /** Freezes values and requires the success or failure shape to be internally consistent. */
        public Resolution {
            values = Objects.requireNonNull(values, "values").deepCopy();
            failureResult = failureResult == null ? Map.of() : Map.copyOf(failureResult);
            reasoning = reasoning == null ? "" : reasoning;
            if (failureResult.isEmpty() != reasoning.isBlank()) {
                throw new IllegalArgumentException("Controlled Feature resolution is inconsistent");
            }
        }

        /** Creates a successful controlled value resolution. */
        public static Resolution values(JsonNode values) {
            return new Resolution(values, Map.of(), "");
        }

        /** Creates an explicit controlled failure without evaluating the Feature backend. */
        public static Resolution failure(JsonNode values, String dependencyStatus, String reasoning) {
            return new Resolution(values, Map.of("dependencyStatus", dependencyStatus), reasoning);
        }

        /** Returns whether the controlled Feature produced a business fallback. */
        public boolean failed() {
            return !failureResult.isEmpty();
        }

        @Override
        public JsonNode values() {
            return values.deepCopy();
        }
    }
}
