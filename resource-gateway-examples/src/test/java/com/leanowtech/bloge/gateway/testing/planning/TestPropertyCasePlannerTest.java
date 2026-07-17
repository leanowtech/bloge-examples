package com.leanowtech.bloge.gateway.testing.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestPropertyCasePlan;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestPropertyCasePlannerTest {

    private final TestPropertyCasePlanner planner = new TestPropertyCasePlanner(
            new ObjectMapper(), new JsonSchemaSampleGenerator());

    @Test
    void reproducesValidatorProvenTrialsAndStrictlySimplerShrinkChainsFromSeed() {
        SchemaEnvelope schema = envelope(Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("age", "name", "enabled", "tags"),
                "properties", Map.of(
                        "age", Map.of("type", "integer", "minimum", -100, "maximum", 100),
                        "name", Map.of("type", "string", "minLength", 2, "maxLength", 12),
                        "enabled", Map.of("type", "boolean"),
                        "tags", Map.of("type", "array", "minItems", 1, "maxItems", 4,
                                "items", Map.of("type", "string", "minLength", 1,
                                        "maxLength", 6)))));

        TestPropertyCasePlan first = planner.plan(target(), schema, 918273645L, 6, 4,
                List.of());
        TestPropertyCasePlan replay = planner.plan(target(), schema, 918273645L, 6, 4,
                List.of());

        assertThat(first).isEqualTo(replay);
        assertThat(first.planFingerprint()).isEqualTo(replay.planFingerprint());
        assertThat(first.status()).isEqualTo(TestPropertyCasePlan.Status.GENERATED);
        assertThat(first.quantification()).isEqualTo(
                TestPropertyCasePlan.Quantification.BOUNDED_SAMPLED);
        assertThat(first.exhaustive()).isFalse();
        assertThat(first.trials()).hasSize(6);
        assertThat(first.trials()).extracting(TestPropertyCasePlan.PropertyTrial::trialId)
                .containsExactly("property-001", "property-002", "property-003",
                        "property-004", "property-005", "property-006");
        assertThat(first.trials()).allSatisfy(trial -> {
            assertAccepted(schema, trial.input());
            assertThat(trial.inputFingerprint()).matches("sha256:[a-f0-9]{64}");
            int previousComplexity = trial.complexity();
            String parentCaseId = trial.trialId();
            for (TestPropertyCasePlan.ShrinkCandidate shrink : trial.shrinkPath()) {
                assertAccepted(schema, shrink.input());
                assertThat(shrink.parentCaseId()).isEqualTo(parentCaseId);
                assertThat(shrink.complexity()).isLessThan(previousComplexity);
                assertThat(shrink.inputFingerprint()).matches("sha256:[a-f0-9]{64}");
                previousComplexity = shrink.complexity();
                parentCaseId = shrink.caseId();
            }
        });
        assertThat(first.allCases()).hasSizeLessThanOrEqualTo(
                first.policy().maxCases());
        assertThat(first.allCases()).extracting(TestPropertyCasePlan.PlannedCase::caseId)
                .doesNotHaveDuplicates();
    }

    @Test
    void changesTheContentAddressWhenOnlyTheSeedChanges() {
        SchemaEnvelope schema = envelope(Map.of(
                "type", "object", "required", List.of("value"),
                "properties", Map.of("value", Map.of(
                        "type", "integer", "minimum", -1_000_000, "maximum", 1_000_000))));

        TestPropertyCasePlan first = planner.plan(target(), schema, 41L, 4, 2, List.of());
        TestPropertyCasePlan second = planner.plan(target(), schema, 42L, 4, 2, List.of());

        assertThat(second.planFingerprint()).isNotEqualTo(first.planFingerprint());
        assertThat(second.trials()).extracting(TestPropertyCasePlan.PropertyTrial::inputFingerprint)
                .isNotEqualTo(first.trials().stream()
                        .map(TestPropertyCasePlan.PropertyTrial::inputFingerprint).toList());
    }

    @Test
    void reportsUniqueTrialShortfallInsteadOfPaddingALowCardinalityDomain() {
        SchemaEnvelope schema = envelope(Map.of("type", "boolean"));

        TestPropertyCasePlan plan = planner.plan(target(), schema, 7L, 4, 2, List.of());

        assertThat(plan.status()).isEqualTo(TestPropertyCasePlan.Status.PARTIAL);
        assertThat(plan.trials()).hasSize(2);
        assertThat(plan.trials()).extracting(TestPropertyCasePlan.PropertyTrial::inputFingerprint)
                .doesNotHaveDuplicates();
        assertThat(plan.gaps()).extracting(TestPropertyCasePlan.CoverageGap::code)
                .contains(TestPropertyCasePlan.GapCode.UNIQUE_TRIAL_LIMIT_REACHED);
    }

    @Test
    void rejectsOpaqueInvalidAndOutOfPolicyRequestsWithoutInventingSamples() {
        TestPropertyCasePlan opaque = planner.plan(target(), SchemaEnvelope.opaque(),
                1L, 1, 0, List.of());
        TestPropertyCasePlan invalid = planner.plan(target(), envelope(Map.of(
                        "type", "integer", "minimum", 5, "maximum", 1)),
                1L, 1, 0, List.of());
        TestPropertyCasePlan outsideLongDomain = planner.plan(target(), envelope(Map.of(
                        "type", "integer", "exclusiveMinimum", Long.MAX_VALUE)),
                1L, 1, 0, List.of());

        assertThat(opaque.status()).isEqualTo(TestPropertyCasePlan.Status.UNAVAILABLE);
        assertThat(opaque.trials()).isEmpty();
        assertThat(opaque.gaps()).extracting(TestPropertyCasePlan.CoverageGap::code)
                .contains(TestPropertyCasePlan.GapCode.OPAQUE_INPUT_SCHEMA);
        assertThat(invalid.status()).isEqualTo(TestPropertyCasePlan.Status.UNAVAILABLE);
        assertThat(invalid.gaps()).extracting(TestPropertyCasePlan.CoverageGap::code)
                .contains(TestPropertyCasePlan.GapCode.INVALID_INPUT_SCHEMA);
        assertThat(outsideLongDomain.status()).isEqualTo(TestPropertyCasePlan.Status.UNAVAILABLE);
        assertThat(outsideLongDomain.trials()).isEmpty();
        assertThat(outsideLongDomain.gaps()).extracting(TestPropertyCasePlan.CoverageGap::code)
                .contains(TestPropertyCasePlan.GapCode.CANDIDATE_NOT_PROVEN);
        assertThatThrownBy(() -> planner.plan(target(), envelope(Map.of("type", "string")),
                1L, TestPropertyCasePlanner.MAX_TRIALS + 1, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trialCount");
        assertThatThrownBy(() -> planner.plan(target(), envelope(Map.of("type", "string")),
                1L, 1, TestPropertyCasePlanner.MAX_SHRINK_STEPS + 1, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxShrinkSteps");
    }

    @Test
    void freezesNestedInputsAndDisclosesUnexpandedConstraints() {
        SchemaEnvelope schema = envelope(Map.of(
                "type", "object", "required", List.of("code"),
                "properties", Map.of("code", Map.of(
                        "type", "string", "minLength", 6, "pattern", "^[a-z]+$"))));

        TestPropertyCasePlan plan = planner.plan(target(), schema, 99L, 2, 2, List.of());

        assertThat(plan.status()).isEqualTo(TestPropertyCasePlan.Status.PARTIAL);
        assertThat(plan.gaps()).anySatisfy(gap -> {
            assertThat(gap.code()).isEqualTo(
                    TestPropertyCasePlan.GapCode.CONSTRAINT_NOT_GENERATED);
            assertThat(gap.keyword()).isEqualTo("pattern");
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) plan.trials().getFirst().input();
        assertThatThrownBy(() -> input.put("other", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void freezesGeneratedCollectionsWithoutRejectingSchemaValidNullItems() {
        SchemaEnvelope schema = envelope(Map.of(
                "type", "array", "minItems", 1, "maxItems", 1,
                "items", Map.of("type", "null")));

        TestPropertyCasePlan plan = planner.plan(target(), schema, 11L, 1, 0, List.of());

        assertThat(plan.status()).isEqualTo(TestPropertyCasePlan.Status.GENERATED);
        @SuppressWarnings("unchecked")
        List<Object> input = (List<Object>) plan.trials().getFirst().input();
        assertThat(input).containsExactly((Object) null);
        assertThatThrownBy(() -> input.add("invalid"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void acceptsNullConstWhenBuildingTheShrinkCandidateSet() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("const", null);

        TestPropertyCasePlan plan = planner.plan(target(), envelope(schema),
                12L, 1, 2, List.of());

        assertThat(plan.status()).isEqualTo(TestPropertyCasePlan.Status.GENERATED);
        assertThat(plan.trials()).singleElement().satisfies(trial -> {
            assertThat(trial.input()).isNull();
            assertThat(trial.shrinkPath()).isEmpty();
        });
    }

    private static void assertAccepted(SchemaEnvelope schema, Object input) {
        assertThat(VisualSchemaValidator.validateValue(schema, input, "/input"))
                .filteredOn(VisualDiagnostic::error)
                .isEmpty();
    }

    private static TestExecutionApiRequest.Target target() {
        return new TestExecutionApiRequest.Target("GRAPH", "customer-policy",
                "sha256:" + "a".repeat(64));
    }

    private static SchemaEnvelope envelope(Map<String, Object> schema) {
        return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema);
    }
}
