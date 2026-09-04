package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure deterministic evaluator for a previously validated unique-hit Scenario tree.
 *
 * <p>The evaluator performs no feature collection, external read, write, interaction or waiting.
 * It consumes already-collected values and returns one terminal or Instruction outlet with resolved
 * bindings and a rule path suitable for payload-filtered execution evidence.</p>
 */
public final class ScenarioTreeEvaluator {
    private final SolutionEntityRegistry registry;
    private final int maximumDepth;

    /** Creates an evaluator with the same positive bound used by static validation. */
    public ScenarioTreeEvaluator(SolutionEntityRegistry registry, int maximumDepth) {
        this.registry = Objects.requireNonNull(registry, "registry");
        if (maximumDepth < 1) throw new IllegalArgumentException("maximumDepth must be positive");
        this.maximumDepth = maximumDepth;
    }

    /** Validates then evaluates one tree against immutable feature values. */
    public Outcome evaluate(String scopeKey, String rootScenarioRef, JsonNode featureValues) {
        new ScenarioTreeValidator(registry, maximumDepth).validate(scopeKey, rootScenarioRef);
        if (featureValues == null || !featureValues.isObject()) {
            throw new SolutionContractException(
                    "SCENARIO_INPUT_INVALID", "Scenario feature values must be an object.");
        }
        return evaluate(scopeKey, rootScenarioRef, (ObjectNode) featureValues.deepCopy(),
                1, new ArrayList<>());
    }

    private Outcome evaluate(String scopeKey,
                             String scenarioRef,
                             ObjectNode values,
                             int depth,
                             List<String> path) {
        if (depth > maximumDepth) throw new SolutionContractException(
                "SCENARIO_TREE_TOO_DEEP", "Scenario tree exceeds the configured depth.");
        ScenarioContract scenario;
        try {
            scenario = registry.requireScenario(scopeKey, scenarioRef);
        } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            throw new SolutionContractException(
                    "SCENARIO_OUTLET_UNRESOLVED", "A Scenario outlet is unresolved.");
        }
        List<ScenarioContract.Rule> matches = scenario.rules().stream()
                .filter(rule -> matches(rule.when(), values)).toList();
        if (matches.size() > 1) throw new SolutionContractException(
                "SCENARIO_HIT_NOT_UNIQUE", "More than one Scenario rule matched.");
        ScenarioContract.Outlet outlet;
        String ruleId;
        if (matches.isEmpty()) {
            outlet = scenario.otherwise();
            ruleId = "otherwise";
        } else {
            ScenarioContract.Rule rule = matches.getFirst();
            outlet = rule.outlet();
            ruleId = rule.ruleId();
        }
        ArrayList<String> nextPath = new ArrayList<>(path);
        nextPath.add(ruleId);
        Map<String, Object> bind = resolve(outlet.bind(), values);
        if (outlet.kind() == ScenarioContract.OutletKind.SUB_SCENARIO) {
            ObjectNode rebound = values.deepCopy();
            outlet.bind().forEach((target, source) -> {
                JsonNode value = values.path(source);
                if (value.isMissingNode()) throw new SolutionContractException(
                        "SCENARIO_BIND_INCOMPLETE", "A Scenario outlet binding is incomplete.");
                rebound.set(target, value.deepCopy());
            });
            return evaluate(scopeKey, outlet.ref(), rebound, depth + 1, nextPath);
        }
        return new Outcome(outlet.kind().name(), outlet.ref(), outlet.terminalKind(),
                Map.copyOf(bind), List.copyOf(nextPath));
    }

    private static boolean matches(JsonNode predicates, ObjectNode values) {
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = predicates.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!matchesPredicate(values.path(field.getKey()), field.getValue())) return false;
        }
        return true;
    }

    private static boolean matchesPredicate(JsonNode actual, JsonNode predicate) {
        if (!predicate.isObject()) return Objects.equals(actual, predicate);
        java.util.Iterator<Map.Entry<String, JsonNode>> operators = predicate.fields();
        while (operators.hasNext()) {
            Map.Entry<String, JsonNode> operation = operators.next();
            boolean matched = switch (operation.getKey()) {
                case "eq" -> Objects.equals(actual, operation.getValue());
                case "ne" -> !Objects.equals(actual, operation.getValue());
                case "in" -> operation.getValue().isArray()
                        && iterable(operation.getValue()).stream().anyMatch(actual::equals);
                case "lt" -> compare(actual, operation.getValue()) < 0;
                case "lte" -> compare(actual, operation.getValue()) <= 0;
                case "gt" -> compare(actual, operation.getValue()) > 0;
                case "gte" -> compare(actual, operation.getValue()) >= 0;
                default -> throw new SolutionContractException(
                        "SCENARIO_PREDICATE_UNSUPPORTED", "A Scenario predicate is unsupported.");
            };
            if (!matched) return false;
        }
        return true;
    }

    private static int compare(JsonNode left, JsonNode right) {
        if (!left.isNumber() || !right.isNumber()) throw new SolutionContractException(
                "SCENARIO_PREDICATE_TYPE_MISMATCH", "A Scenario comparison has incompatible types.");
        return new BigDecimal(left.asText()).compareTo(new BigDecimal(right.asText()));
    }

    private static List<JsonNode> iterable(JsonNode array) {
        ArrayList<JsonNode> values = new ArrayList<>();
        array.forEach(values::add);
        return values;
    }

    private static Map<String, Object> resolve(Map<String, String> bindings, ObjectNode values) {
        LinkedHashMap<String, Object> resolved = new LinkedHashMap<>();
        bindings.forEach((target, source) -> {
            JsonNode value = values.path(source);
            if (value.isMissingNode()) throw new SolutionContractException(
                    "SCENARIO_BIND_INCOMPLETE", "A Scenario outlet binding is incomplete.");
            resolved.put(target, scalar(value));
        });
        return resolved;
    }

    private static Object scalar(JsonNode value) {
        if (value.isTextual()) return value.asText();
        if (value.isBoolean()) return value.asBoolean();
        if (value.isIntegralNumber()) return value.asLong();
        if (value.isFloatingPointNumber()) return value.decimalValue();
        if (value.isNull() || value.isMissingNode()) return null;
        return value.deepCopy();
    }

    /** One finite decision result and the ordered rules traversed to reach it. */
    public record Outcome(
            String outletKind,
            String ref,
            String terminalKind,
            Map<String, Object> bind,
            List<String> rulePath
    ) {
        /** Freezes the pure result. */
        public Outcome {
            outletKind = outletKind == null ? "" : outletKind;
            ref = ref == null ? "" : ref;
            terminalKind = terminalKind == null ? "" : terminalKind;
            bind = bind == null ? Map.of() : Map.copyOf(bind);
            rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
        }
    }
}
