package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Evaluates the shared graph/operator assertion subset without executing user expressions. */
public class TestAssertionEvaluator {

    private final ObjectMapper objectMapper;

    /** @param objectMapper mapper used for canonical tree and JSON Pointer access */
    public TestAssertionEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Evaluates assertions after graph execution and fixture consumption are complete.
     *
     * @param assertions frozen declarative assertions
     * @param graph test target
     * @param result graph result
     * @param fixtureUses per-rule observed uses
     * @return immutable assertion facts
     */
    public List<TestRunEvidence.AssertionResult> evaluate(
            List<FixtureBundle.Assertion> assertions,
            Graph graph,
            GraphResult result,
            Map<String, Integer> fixtureUses) {
        List<TestRunEvidence.AssertionResult> evaluated = new ArrayList<>();
        for (FixtureBundle.Assertion assertion : assertions) {
            evaluated.add(evaluate(assertion, graph, result, fixtureUses));
        }
        return List.copyOf(evaluated);
    }

    private TestRunEvidence.AssertionResult evaluate(FixtureBundle.Assertion assertion,
                                                     Graph graph, GraphResult result,
                                                     Map<String, Integer> fixtureUses) {
        String scope = assertion.scope().isBlank() ? "OUTPUT_PATH" : assertion.scope().toUpperCase();
        Object source;
        String path = assertion.path();
        switch (scope) {
            case "GRAPH_SUCCESS" -> source = result != null && result.isSuccess();
            case "NODE_STATUS" -> source = result == null ? null : result.statusMap().get(assertion.nodeId());
            case "FIXTURE_USES" -> source = fixtureUses.getOrDefault(assertion.nodeId(), 0);
            case "NODE_OUTPUT" -> source = result == null ? null
                    : result.findOutput(assertion.nodeId(), Object.class).orElse(null);
            case "OUTPUT_PATH" -> source = terminalOutput(graph, result, assertion.nodeId());
            default -> {
                return new TestRunEvidence.AssertionResult(scope, path, false, assertion.expected(), null,
                        "Unsupported assertion scope: " + scope);
            }
        }
        Object actual = pointer(source, path);
        boolean passed = compare(assertion.operator(), assertion.expected(), actual,
                assertion.numericTolerance());
        return new TestRunEvidence.AssertionResult(scope, path, passed, assertion.expected(), actual,
                passed ? "" : "Assertion " + normalizedOperator(assertion.operator()) + " failed.");
    }

    private Object terminalOutput(Graph graph, GraphResult result, String preferredNode) {
        if (result == null) {
            return null;
        }
        if (preferredNode != null && !preferredNode.isBlank()) {
            return result.findOutput(preferredNode, Object.class).orElse(null);
        }
        return graph.terminalNodes().stream().sorted()
                .map(node -> result.findOutput(node, Object.class).orElse(null))
                .filter(Objects::nonNull).findFirst().orElse(null);
    }

    private Object pointer(Object source, String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return source;
        }
        JsonNode selected = objectMapper.valueToTree(source).at(path);
        return selected.isMissingNode() ? MissingValue.INSTANCE
                : objectMapper.convertValue(selected, Object.class);
    }

    private static boolean compare(String operator, Object expected, Object actual,
                                   Double tolerance) {
        String normalized = normalizedOperator(operator);
        if ("EXISTS".equals(normalized)) return actual != MissingValue.INSTANCE;
        if ("ABSENT".equals(normalized)) return actual == MissingValue.INSTANCE;
        if (actual == MissingValue.INSTANCE) return false;
        if ("EQUALS".equals(normalized) && tolerance != null
                && actual instanceof Number && expected instanceof Number) {
            BigDecimal delta = decimal(actual).subtract(decimal(expected)).abs();
            return delta.compareTo(BigDecimal.valueOf(Math.abs(tolerance))) <= 0;
        }
        return switch (normalized) {
            case "EQUALS" -> Objects.equals(expected, actual);
            case "NOT_EQUALS" -> !Objects.equals(expected, actual);
            case "GREATER_THAN" -> compareNumbers(actual, expected, comparison -> comparison > 0);
            case "GREATER_OR_EQUAL" -> compareNumbers(actual, expected, comparison -> comparison >= 0);
            case "LESS_THAN" -> compareNumbers(actual, expected, comparison -> comparison < 0);
            case "LESS_OR_EQUAL" -> compareNumbers(actual, expected, comparison -> comparison <= 0);
            case "CONTAINS" -> actual instanceof String text && text.contains(String.valueOf(expected));
            default -> false;
        };
    }

    private static boolean compareNumbers(Object actual, Object expected,
                                          java.util.function.IntPredicate predicate) {
        if (!(actual instanceof Number) || !(expected instanceof Number)) {
            return false;
        }
        return predicate.test(decimal(actual).compareTo(decimal(expected)));
    }

    private static BigDecimal decimal(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    private static String normalizedOperator(String operator) {
        return operator == null || operator.isBlank() ? "EQUALS" : operator.trim().toUpperCase();
    }

    private enum MissingValue {
        INSTANCE
    }
}
