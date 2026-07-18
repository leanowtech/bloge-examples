package com.leanowtech.bloge.gateway.testing.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteV5Test {
    private static final String TARGET = fingerprint('a');
    private static final String SOURCE = fingerprint('b');
    private static final String GRAPH = fingerprint('c');
    private static final String PLAN = fingerprint('d');
    private static final String ORACLE = fingerprint('e');

    @Test
    void freezesTheCompleteBoundedMutationAndOracleClosure() {
        List<Object> callerOwned = new ArrayList<>(List.of("value"));
        TestSuite.TestCase testCase = caseWithInput(Map.of("items", callerOwned));

        TestSuiteV5 suite = suite(List.of(testCase), mutants(2), List.of(),
                TestSuiteV5.SourcePlanStatus.GENERATED, false);
        callerOwned.add("later");

        assertThat(((Map<?, ?>) suite.cases().getFirst().input()).get("items"))
                .isEqualTo(List.of("value"));
        assertThatThrownBy(() -> ((List<Object>) ((Map<?, ?>) suite.cases().getFirst().input())
                .get("items")).add("mutation"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(suite.mutants()).extracting(TestSuiteV5.MutantRef::mutantId)
                .containsExactly("mutant-001", "mutant-002");
    }

    @Test
    void rejectsUnacceptedGapsAndUnboundedExecutionMatrices() {
        TestSuiteV5.PlanningGap gap = new TestSuiteV5.PlanningGap(
                TestSuiteV5.PlanningGapCode.NESTED_SCOPE_NOT_EXPANDED,
                "/members/1", "FOREACH");

        assertThatThrownBy(() -> suite(List.of(caseWithInput(Map.of())), mutants(1),
                List.of(gap), TestSuiteV5.SourcePlanStatus.PARTIAL, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explicit acceptance");

        List<TestSuite.TestCase> seventeenCases = java.util.stream.IntStream.rangeClosed(1, 17)
                .mapToObj(index -> new TestSuite.TestCase("case-" + index,
                        TestSuite.CaseType.REGRESSION, Map.of("index", index), fixtureRef(),
                        List.of(), Map.of())).toList();
        assertThatThrownBy(() -> suite(seventeenCases, mutants(16), List.of(),
                TestSuiteV5.SourcePlanStatus.GENERATED, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("execution matrix");
    }

    private static TestSuiteV5 suite(
            List<TestSuite.TestCase> cases,
            List<TestSuiteV5.MutantRef> mutants,
            List<TestSuiteV5.PlanningGap> gaps,
            TestSuiteV5.SourcePlanStatus status,
            boolean gapsAccepted) {
        return new TestSuiteV5("", "orders-mutations", 1,
                new TestSuite.Target("GRAPH", "orders", TARGET), "INTERNAL", cases,
                new TestSuite.CoveragePolicy(1, List.of(), List.of(), List.of(), 1, false),
                SemanticCoveragePolicy.empty(), new TestSuite.PromotionPolicy(true, 1, true),
                TestSuiteV5.EvaluationMode.PURE_DSL_MUTATION, TestSuiteV5.SOURCE_FORMAT,
                SOURCE, GRAPH, PLAN, policy(mutants.size()), status, gapsAccepted, gaps, mutants,
                new TestSuiteV5.OracleSuiteRef(
                        "orders-oracle", 7, ORACLE, TestSuite.SCHEMA_VERSION),
                new TestSuiteV5.MutationScorePolicy(8_000, 0, false, false), Map.of());
    }

    private static TestSuiteV5.MutationPolicy policy(int maximum) {
        return new TestSuiteV5.MutationPolicy(TestSuiteV5.PLANNER_VERSION, maximum,
                TestSuiteV5.SOURCE_FORMAT, TestSuiteV5.VERIFICATION_MODE, false, false);
    }

    private static List<TestSuiteV5.MutantRef> mutants(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count).mapToObj(index ->
                new TestSuiteV5.MutantRef("mutant-%03d".formatted(index),
                        TestSuiteV5.MutationKind.DECISION_CONDITION_NEGATED,
                        "/members/%d/predicate".formatted(index), index, 1,
                        indexedFingerprint(index), indexedFingerprint(100 + index),
                        indexedFingerprint(200 + index),
                        TestSuiteV5.EquivalenceClassification.UNKNOWN)).toList();
    }

    private static TestSuite.TestCase caseWithInput(Object input) {
        return new TestSuite.TestCase("golden", TestSuite.CaseType.GOLDEN, input,
                fixtureRef(), List.of(), Map.of());
    }

    private static TestSuite.FixtureBundleRef fixtureRef() {
        return new TestSuite.FixtureBundleRef("orders-fixture", 3, fingerprint('z'));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static String indexedFingerprint(int value) {
        return "sha256:" + "%064x".formatted(value);
    }
}
