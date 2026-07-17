package com.leanowtech.bloge.gateway.testing.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteV4Test {
    private static final String PLAN = "sha256:" + "a".repeat(64);
    private static final String SCHEMA = "sha256:" + "b".repeat(64);
    private static final String TARGET = "sha256:" + "c".repeat(64);
    private static final String FIXTURE = "sha256:" + "d".repeat(64);
    private static final String ROOT_INPUT = "sha256:" + "e".repeat(64);
    private static final String SHRINK_INPUT = "sha256:" + "f".repeat(64);

    @Test
    void freezesNestedInputsAndRetainsHonestPropertyClosure() {
        Map<String, Object> nested = new LinkedHashMap<>();
        List<Object> values = new ArrayList<>(List.of("seeded"));
        nested.put("values", values);
        TestSuiteV4 suite = suite(nested, fixture(), false,
                TestSuiteV4.SourcePlanStatus.GENERATED, false, List.of());

        values.add("mutated-after-construction");

        assertThat(suite.schemaVersion()).isEqualTo(TestSuiteV4.SCHEMA_VERSION);
        assertThat(suite.quantification()).isEqualTo(TestSuiteV4.Quantification.BOUNDED_SAMPLED);
        assertThat(suite.exhaustive()).isFalse();
        assertThat(suite.cases()).extracting(TestSuite.TestCase::caseId)
                .containsExactly("property-001", "property-001-shrink-001");
        assertThat((List<Object>) ((Map<?, ?>) suite.cases().getFirst().input()).get("values"))
                .containsExactly("seeded");
        assertThatThrownBy(() -> ((Map<Object, Object>) suite.cases().getFirst().input())
                .put("later", true)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> suite.propertyTrials().add(suite.propertyTrials().getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsExhaustiveClaimsUnacceptedGapsBrokenLineageAndFixtureVariation() {
        assertThatThrownBy(() -> suite(Map.of("value", 2), fixture(), true,
                TestSuiteV4.SourcePlanStatus.GENERATED, false, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-exhaustive");
        assertThatThrownBy(() -> suite(Map.of("value", 2), fixture(), false,
                TestSuiteV4.SourcePlanStatus.PARTIAL, false,
                List.of(new TestSuiteV4.PropertyGenerationGap(
                        TestSuiteV4.GenerationGapCode.CONSTRAINT_NOT_GENERATED, "/", "pattern"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explicit acceptance");

        TestSuiteV4 valid = suite(Map.of("value", 2), fixture(), false,
                TestSuiteV4.SourcePlanStatus.GENERATED, false, List.of());
        List<TestSuiteV4.PropertyTrialRef> broken = List.of(new TestSuiteV4.PropertyTrialRef(
                "property-001", ROOT_INPUT, 2,
                List.of(new TestSuiteV4.PropertyShrinkRef(
                        "property-001-shrink-001", "not-the-parent", 1,
                        SHRINK_INPUT, 1))));
        assertThatThrownBy(() -> copy(valid, valid.cases(), broken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("linear and strictly simpler");

        TestSuite.FixtureBundleRef another = new TestSuite.FixtureBundleRef(
                "fixture-two", 1, "sha256:" + "9".repeat(64));
        List<TestSuite.TestCase> mixed = List.of(valid.cases().getFirst(),
                new TestSuite.TestCase("property-001-shrink-001", TestSuite.CaseType.PROPERTY,
                        Map.of("value", 1), another, List.of(), Map.of()));
        assertThatThrownBy(() -> copy(valid, mixed, valid.propertyTrials()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one exact governed fixture");
    }

    private static TestSuiteV4 suite(
            Object rootInput,
            TestSuite.FixtureBundleRef fixture,
            boolean exhaustive,
            TestSuiteV4.SourcePlanStatus status,
            boolean gapsAccepted,
            List<TestSuiteV4.PropertyGenerationGap> gaps) {
        List<TestSuite.TestCase> cases = List.of(
                new TestSuite.TestCase("property-001", TestSuite.CaseType.PROPERTY,
                        rootInput, fixture, List.of("property-root"), Map.of("role", "ROOT")),
                new TestSuite.TestCase("property-001-shrink-001", TestSuite.CaseType.PROPERTY,
                        Map.of("value", 1), fixture,
                        List.of("property-shrink"), Map.of("role", "SHRINK")));
        TestSuiteV4.PropertyGenerationPolicy policy = new TestSuiteV4.PropertyGenerationPolicy(
                "property-cases-v1", 42, 1, 1, 2, 32, 8, 32,
                "DRAFT_2020_12_SHARED_VALIDATOR");
        return new TestSuiteV4("", "property-suite", 3,
                new TestSuite.Target("GRAPH", "orders", TARGET), "INTERNAL", cases,
                new TestSuite.CoveragePolicy(2, List.of(TestSuite.CaseType.PROPERTY),
                        List.of(), List.of(), 1, false),
                SemanticCoveragePolicy.empty(), new TestSuite.PromotionPolicy(true, 2, true),
                TestSuiteV4.EvaluationMode.PROPERTY_EXECUTION,
                TestSuiteV4.Quantification.BOUNDED_SAMPLED, exhaustive, PLAN, SCHEMA, policy,
                status, gapsAccepted, gaps,
                List.of(new TestSuiteV4.PropertyTrialRef("property-001", ROOT_INPUT, 2,
                        List.of(new TestSuiteV4.PropertyShrinkRef(
                                "property-001-shrink-001", "property-001", 1,
                                SHRINK_INPUT, 1)))),
                Map.of("source", "property-plan"));
    }

    private static TestSuiteV4 copy(
            TestSuiteV4 source,
            List<TestSuite.TestCase> cases,
            List<TestSuiteV4.PropertyTrialRef> trials) {
        return new TestSuiteV4(source.schemaVersion(), source.suiteId(), source.revision(),
                source.target(), source.classification(), cases, source.coveragePolicy(),
                source.semanticCoveragePolicy(), source.promotionPolicy(), source.evaluationMode(),
                source.quantification(), source.exhaustive(), source.propertyPlanFingerprint(),
                source.inputSchemaFingerprint(), source.generationPolicy(), source.sourcePlanStatus(),
                source.generationGapsAccepted(), source.generationGaps(), trials, source.metadata());
    }

    private static TestSuite.FixtureBundleRef fixture() {
        return new TestSuite.FixtureBundleRef("fixture", 7, FIXTURE);
    }
}
