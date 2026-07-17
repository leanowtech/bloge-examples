package com.leanowtech.bloge.gateway.testing.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestBoundaryCasePlan;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestBoundaryCasePlannerTest {

    private final TestBoundaryCasePlanner planner = new TestBoundaryCasePlanner(
            new ObjectMapper(), new JsonSchemaSampleGenerator());

    @Test
    void generatesAndProvesStructuralScalarAndCollectionBoundaries() {
        SchemaEnvelope schema = envelope(Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("age", "name", "tags"),
                "properties", Map.of(
                        "age", Map.of("type", "integer", "minimum", 0, "maximum", 100),
                        "name", Map.of("type", "string", "minLength", 2, "maxLength", 4),
                        "tags", Map.of("type", "array", "minItems", 1, "maxItems", 2,
                                "items", Map.of("type", "string")))));

        TestBoundaryCasePlan plan = planner.plan(target(), schema, List.of());

        assertThat(plan.status()).isEqualTo(TestBoundaryCasePlan.Status.GENERATED);
        assertThat(plan.gaps()).isEmpty();
        assertThat(plan.planFingerprint()).matches("sha256:[a-f0-9]{64}");
        assertThat(plan.cases()).extracting(TestBoundaryCasePlan.BoundaryCase::kind)
                .contains(
                        TestBoundaryCasePlan.BoundaryKind.BASELINE,
                        TestBoundaryCasePlan.BoundaryKind.REQUIRED_PROPERTY_MISSING,
                        TestBoundaryCasePlan.BoundaryKind.UNKNOWN_PROPERTY,
                        TestBoundaryCasePlan.BoundaryKind.TYPE_MISMATCH,
                        TestBoundaryCasePlan.BoundaryKind.MINIMUM,
                        TestBoundaryCasePlan.BoundaryKind.BELOW_MINIMUM,
                        TestBoundaryCasePlan.BoundaryKind.MAXIMUM,
                        TestBoundaryCasePlan.BoundaryKind.ABOVE_MAXIMUM,
                        TestBoundaryCasePlan.BoundaryKind.MIN_LENGTH,
                        TestBoundaryCasePlan.BoundaryKind.BELOW_MIN_LENGTH,
                        TestBoundaryCasePlan.BoundaryKind.MAX_LENGTH,
                        TestBoundaryCasePlan.BoundaryKind.ABOVE_MAX_LENGTH,
                        TestBoundaryCasePlan.BoundaryKind.MIN_ITEMS,
                        TestBoundaryCasePlan.BoundaryKind.BELOW_MIN_ITEMS,
                        TestBoundaryCasePlan.BoundaryKind.MAX_ITEMS,
                        TestBoundaryCasePlan.BoundaryKind.ABOVE_MAX_ITEMS);
        assertThat(plan.cases()).allSatisfy(testCase -> {
            if (testCase.expectedOutcome() == TestBoundaryCasePlan.ExpectedOutcome.ACCEPTED) {
                assertThat(testCase.validationCodes()).isEmpty();
            } else {
                assertThat(testCase.validationCodes()).isNotEmpty();
            }
        });
        assertThat(plan.cases()).filteredOn(testCase ->
                        testCase.kind() == TestBoundaryCasePlan.BoundaryKind.BELOW_MINIMUM)
                .singleElement().satisfies(testCase ->
                        assertThat(testCase.validationCodes())
                                .contains("visual.context.numericConstraintMismatch"));
    }

    @Test
    void producesStableFingerprintAndDefensivelyFreezesNestedInputs() {
        SchemaEnvelope schema = envelope(Map.of(
                "type", "object", "additionalProperties", false,
                "required", List.of("items"),
                "properties", Map.of("items", Map.of(
                        "type", "array", "minItems", 1,
                        "items", Map.of("type", "string")))));

        TestBoundaryCasePlan first = planner.plan(target(), schema, List.of());
        TestBoundaryCasePlan second = planner.plan(target(), schema, List.of());

        assertThat(second).isEqualTo(first);
        assertThat(second.planFingerprint()).isEqualTo(first.planFingerprint());
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) first.cases().getFirst().input();
        assertThatThrownBy(() -> input.put("other", true))
                .isInstanceOf(UnsupportedOperationException.class);
        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) input.get("items");
        assertThatThrownBy(() -> items.add("mutated"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void refusesOpaqueAndInvalidSchemasInsteadOfInventingCoverage() {
        TestBoundaryCasePlan opaque = planner.plan(target(), SchemaEnvelope.opaque(), List.of());
        TestBoundaryCasePlan unconstrained = planner.plan(target(), envelope(Map.of()), List.of());
        TestBoundaryCasePlan any = planner.plan(target(), envelope(Map.of("kind", "any")), List.of());
        TestBoundaryCasePlan invalid = planner.plan(target(), envelope(Map.of(
                "type", "integer", "minimum", 10, "maximum", 1)), List.of());

        assertThat(opaque.status()).isEqualTo(TestBoundaryCasePlan.Status.UNAVAILABLE);
        assertThat(opaque.cases()).isEmpty();
        assertThat(opaque.gaps()).extracting(TestBoundaryCasePlan.CoverageGap::code)
                .containsExactly(TestBoundaryCasePlan.GapCode.OPAQUE_INPUT_SCHEMA);
        assertThat(unconstrained.status()).isEqualTo(TestBoundaryCasePlan.Status.UNAVAILABLE);
        assertThat(any.status()).isEqualTo(TestBoundaryCasePlan.Status.UNAVAILABLE);
        assertThat(invalid.status()).isEqualTo(TestBoundaryCasePlan.Status.UNAVAILABLE);
        assertThat(invalid.gaps()).extracting(TestBoundaryCasePlan.CoverageGap::code)
                .contains(TestBoundaryCasePlan.GapCode.INVALID_INPUT_SCHEMA);
    }

    @Test
    void disclosesUnexpandedConstraintsEvenWhenBaselineIsValid() {
        SchemaEnvelope schema = envelope(Map.of(
                "type", "object", "required", List.of("code"),
                "properties", Map.of("code", Map.of(
                        "type", "string", "pattern", "^string$"))));

        TestBoundaryCasePlan plan = planner.plan(target(), schema, List.of());

        assertThat(plan.status()).isEqualTo(TestBoundaryCasePlan.Status.PARTIAL);
        assertThat(plan.cases()).isNotEmpty();
        assertThat(plan.gaps()).anySatisfy(gap -> {
            assertThat(gap.code()).isEqualTo(
                    TestBoundaryCasePlan.GapCode.CONSTRAINT_NOT_BOUNDARY_EXPANDED);
            assertThat(gap.keyword()).isEqualTo("pattern");
        });
    }

    @Test
    void disclosesNullableTypeBranchInsteadOfClaimingExhaustiveGeneration() {
        TestBoundaryCasePlan plan = planner.plan(target(), envelope(Map.of(
                "type", List.of("string", "null"), "minLength", 1)), List.of());

        assertThat(plan.status()).isEqualTo(TestBoundaryCasePlan.Status.PARTIAL);
        assertThat(plan.gaps()).anySatisfy(gap -> {
            assertThat(gap.code()).isEqualTo(
                    TestBoundaryCasePlan.GapCode.CONSTRAINT_NOT_BOUNDARY_EXPANDED);
            assertThat(gap.keyword()).isEqualTo("type");
        });
    }

    @Test
    void requiresTheIntendedDiagnosticRatherThanAnyIncidentalRejection() {
        SchemaEnvelope schema = envelope(Map.of("type", "boolean", "enum", List.of(true, false)));

        TestBoundaryCasePlan plan = planner.plan(target(), schema, List.of());

        assertThat(plan.status()).isEqualTo(TestBoundaryCasePlan.Status.PARTIAL);
        assertThat(plan.cases()).noneMatch(testCase ->
                testCase.kind() == TestBoundaryCasePlan.BoundaryKind.OUTSIDE_ENUM);
        assertThat(plan.gaps()).anySatisfy(gap -> {
            assertThat(gap.code()).isEqualTo(
                    TestBoundaryCasePlan.GapCode.CANDIDATE_NOT_PROVEN);
            assertThat(gap.keyword()).isEqualTo("OUTSIDE_ENUM");
        });
    }

    @Test
    void boundsWideAndDeepSchemasWithStableGapCodes() {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) {
            properties.put("field" + i, Map.of("type", "string"));
        }
        TestBoundaryCasePlan wide = planner.plan(target(), envelope(Map.of(
                "type", "object", "properties", properties,
                "required", List.copyOf(properties.keySet()))), List.of());

        Map<String, Object> nested = Map.of("type", "string");
        for (int i = 0; i < TestBoundaryCasePlanner.MAX_DEPTH + 1; i++) {
            nested = Map.of("type", "object", "properties", Map.of("child", nested));
        }
        TestBoundaryCasePlan deep = planner.plan(target(), envelope(nested), List.of());

        assertThat(wide.cases()).hasSize(TestBoundaryCasePlanner.MAX_CASES);
        assertThat(wide.gaps()).extracting(TestBoundaryCasePlan.CoverageGap::code)
                .contains(TestBoundaryCasePlan.GapCode.CASE_LIMIT_REACHED);
        assertThat(deep.gaps()).extracting(TestBoundaryCasePlan.CoverageGap::code)
                .contains(TestBoundaryCasePlan.GapCode.DEPTH_LIMIT_REACHED);
    }

    @Test
    void ignoresCollectionBoundsOutsideTheSafeIntegerRangeWithoutOverflowing() {
        TestBoundaryCasePlan plan = planner.plan(target(), envelope(Map.of(
                "type", "array", "maxItems", Long.MAX_VALUE,
                "items", Map.of("type", "string"))), List.of());

        assertThat(plan.cases()).noneMatch(testCase ->
                testCase.kind() == TestBoundaryCasePlan.BoundaryKind.MAX_ITEMS
                        || testCase.kind() == TestBoundaryCasePlan.BoundaryKind.ABOVE_MAX_ITEMS);
        assertThat(plan.cases()).hasSizeLessThanOrEqualTo(TestBoundaryCasePlanner.MAX_CASES);
        assertThat(plan.status()).isEqualTo(TestBoundaryCasePlan.Status.PARTIAL);
        assertThat(plan.gaps()).anySatisfy(gap -> {
            assertThat(gap.code()).isEqualTo(
                    TestBoundaryCasePlan.GapCode.COLLECTION_LIMIT_REACHED);
            assertThat(gap.keyword()).isEqualTo("maxItems");
        });
    }

    private static TestExecutionApiRequest.Target target() {
        return new TestExecutionApiRequest.Target("GRAPH", "customer-policy",
                "sha256:" + "a".repeat(64));
    }

    private static SchemaEnvelope envelope(Map<String, Object> schema) {
        return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema);
    }
}
