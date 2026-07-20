package com.leanowtech.bloge.gateway.testing.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteDeepImmutabilityTest {

    @Test
    void v1DetachesAndRecursivelyFreezesCaseInputAndMetadata() {
        List<Object> callerValues = new ArrayList<>(List.of("approved"));
        Map<String, Object> callerInput = new LinkedHashMap<>();
        callerInput.put("nested", callerValues);
        Map<String, Object> caseMetadata = new LinkedHashMap<>();
        caseMetadata.put("facts", callerValues);
        Map<String, Object> suiteMetadata = new LinkedHashMap<>();
        suiteMetadata.put("facts", callerValues);

        TestSuite suite = suite(callerInput, caseMetadata, suiteMetadata);
        callerValues.add("denied");
        callerInput.put("late", true);

        assertThat(suite.cases().getFirst().input())
                .isEqualTo(Map.of("nested", List.of("approved")));
        assertThat(suite.cases().getFirst().metadata())
                .isEqualTo(Map.of("facts", List.of("approved")));
        assertThat(suite.metadata()).isEqualTo(Map.of("facts", List.of("approved")));
        assertThatThrownBy(() -> ((Map<String, Object>) suite.cases().getFirst().input())
                .put("other", true)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<Object>) ((Map<?, ?>) suite.cases().getFirst().input())
                .get("nested")).add("other")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsCyclesExcessiveDepthAndNonStringJsonKeys() {
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("self", cycle);
        assertThatThrownBy(() -> suite(cycle, Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");

        Object nested = "leaf";
        for (int index = 0; index < 129; index++) {
            nested = List.of(nested);
        }
        Object tooDeep = nested;
        assertThatThrownBy(() -> suite(tooDeep, Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nesting depth");

        Map<Object, Object> invalid = new LinkedHashMap<>();
        invalid.put(17, "value");
        assertThatThrownBy(() -> suite(invalid, Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key must be a string");
    }

    private static TestSuite suite(Object input, Map<String, Object> caseMetadata,
                                   Map<String, Object> suiteMetadata) {
        return new TestSuite("", "suite-a", 1,
                new TestSuite.Target("GRAPH", "graph-a", "sha256:" + "a".repeat(64)),
                "INTERNAL", List.of(new TestSuite.TestCase("golden",
                TestSuite.CaseType.GOLDEN, input, new TestSuite.FixtureBundleRef(
                "fixture-a", 1, "sha256:" + "b".repeat(64)), List.of(), caseMetadata)),
                TestSuite.CoveragePolicy.defaults(), TestSuite.PromotionPolicy.defaults(),
                suiteMetadata);
    }
}
