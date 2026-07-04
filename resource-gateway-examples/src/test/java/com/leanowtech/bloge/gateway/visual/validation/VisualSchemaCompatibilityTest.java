package com.leanowtech.bloge.gateway.visual.validation;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for visual schema-to-schema compatibility.
 */
class VisualSchemaCompatibilityTest {

    @Test
    void acceptsSourceUnionOnlyWhenEveryBranchCanFeedTarget() {
        Map<String, Object> source = Map.of(
                "anyOf", List.of(
                        Map.of("type", "integer", "minimum", 0),
                        Map.of("type", "integer", "minimum", 10)
                )
        );
        Map<String, Object> target = Map.of("type", "integer", "minimum", 0);

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void rejectsSourceUnionWhenAnyBranchCannotFeedTarget() {
        Map<String, Object> source = Map.of(
                "oneOf", List.of(
                        Map.of("type", "integer"),
                        Map.of("type", "string")
                )
        );
        Map<String, Object> target = Map.of("type", "integer");

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("source oneOf branch 1")
                        .contains("source type string cannot feed target type integer"));
    }

    @Test
    void acceptsTargetAnyOfWhenOneBranchCanReceiveSource() {
        Map<String, Object> source = Map.of("type", "string", "enum", List.of("APPROVE"));
        Map<String, Object> target = Map.of(
                "anyOf", List.of(
                        Map.of("type", "integer"),
                        Map.of("type", "string", "enum", List.of("APPROVE", "REJECT"))
                )
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void rejectsTargetOneOfWhenSourceCouldMatchMultipleBranches() {
        Map<String, Object> source = Map.of("type", "integer");
        Map<String, Object> target = Map.of(
                "oneOf", List.of(
                        Map.of("type", "integer"),
                        Map.of("type", "number")
                )
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("target oneOf is ambiguous")
                        .contains("2 compatible branches"));
    }

    @Test
    void acceptsTargetAllOfWhenSourceCanSatisfyEveryBranch() {
        Map<String, Object> source = Map.of("type", "integer", "minimum", 10, "maximum", 20);
        Map<String, Object> target = Map.of(
                "allOf", List.of(
                        Map.of("type", "integer"),
                        Map.of("type", "integer", "minimum", 0),
                        Map.of("type", "integer", "maximum", 25)
                )
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void rejectsTargetAllOfWhenSourceCannotSatisfyOneBranch() {
        Map<String, Object> source = Map.of("type", "integer", "minimum", 10);
        Map<String, Object> target = Map.of(
                "allOf", List.of(
                        Map.of("type", "integer"),
                        Map.of("type", "integer", "maximum", 5)
                )
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("target allOf branch 1")
                        .contains("target requires value <= 5")
                        .contains("source has no upper bound"));
    }

    @Test
    void acceptsSourceAllOfWhenOneConstituentProvesTargetCompatibility() {
        Map<String, Object> source = Map.of(
                "allOf", List.of(
                        Map.of("type", "integer", "minimum", 10),
                        Map.of("not", Map.of("const", 13))
                )
        );
        Map<String, Object> target = Map.of("type", "integer", "minimum", 0);

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void rejectsSourceAllOfWhenNoConstituentCanProveTargetCompatibility() {
        Map<String, Object> source = Map.of(
                "allOf", List.of(
                        Map.of("type", "string"),
                        Map.of("pattern", "^[A-Z]+$")
                )
        );
        Map<String, Object> target = Map.of("type", "integer");

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("source allOf has no constituent")
                        .contains("branch 0")
                        .contains("source type string cannot feed target type integer")
                        .contains("branch 1 has no explicit type or finite domain"));
    }

    @Test
    void matchesValuesAgainstAllOfBranches() {
        Map<String, Object> schema = Map.of(
                "allOf", List.of(
                        Map.of("type", "string"),
                        Map.of("minLength", 3),
                        Map.of("not", Map.of("const", "BAD"))
                )
        );

        assertThat(VisualSchemaCompatibility.valueMatchesSchema("GOOD", schema)).isTrue();
        assertThat(VisualSchemaCompatibility.valueMatchesSchema("NO", schema)).isFalse();
        assertThat(VisualSchemaCompatibility.valueMatchesSchema("BAD", schema)).isFalse();
        assertThat(VisualSchemaCompatibility.schemaTypeLabel(schema)).isEqualTo("allOf<string&unknown&unknown>");
    }

    @Test
    void matchesValuesAgainstIfThenElseBranches() {
        Map<String, Object> schema = conditionalPaymentSchema();

        assertThat(VisualSchemaCompatibility.valueMatchesSchema(Map.of(
                "paymentMethod", "CARD",
                "cardNumber", "41111111"
        ), schema)).isTrue();
        assertThat(VisualSchemaCompatibility.valueMatchesSchema(Map.of(
                "paymentMethod", "CARD",
                "bankAccount", "BA-1"
        ), schema)).isFalse();
        assertThat(VisualSchemaCompatibility.valueMatchesSchema(Map.of(
                "paymentMethod", "BANK",
                "bankAccount", "BA-1"
        ), schema)).isTrue();
        assertThat(VisualSchemaCompatibility.valueMatchesSchema(Map.of(
                "paymentMethod", "BANK"
        ), schema)).isFalse();
    }

    @Test
    void matchesArrayValuesAgainstUnevaluatedItems() {
        Map<String, Object> schema = tupleSchemaWithUnevaluatedItems(false);

        assertThat(VisualSchemaCompatibility.valueMatchesSchema(List.of("risk", 7), schema)).isTrue();
        assertThat(VisualSchemaCompatibility.valueMatchesSchema(List.of("risk", 7, "extra"), schema)).isFalse();
        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(schema, schema)).isEmpty();
    }

    @Test
    void matchesArrayValuesAgainstBooleanItemsPolicy() {
        Map<String, Object> schema = tupleSchemaWithItems(false);

        assertThat(VisualSchemaCompatibility.valueMatchesSchema(List.of("risk", 7), schema)).isTrue();
        assertThat(VisualSchemaCompatibility.valueMatchesSchema(List.of("risk", 7, "extra"), schema)).isFalse();
        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(schema, schema)).isEmpty();
    }

    @Test
    void rejectsSourceResidualItemsWhenTargetForbidsItems() {
        Map<String, Object> source = Map.of(
                "type", "array",
                "prefixItems", List.of(Map.of("type", "string")),
                "items", Map.of("type", "string")
        );
        Map<String, Object> target = Map.of(
                "type", "array",
                "prefixItems", List.of(Map.of("type", "string")),
                "items", false
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("target requires item count <= 1"));
    }

    @Test
    void acceptsArrayContainsWhenSourceItemsAndMinItemsGuaranteeMatches() {
        Map<String, Object> source = Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "minItems", 2
        );
        Map<String, Object> target = Map.of(
                "type", "array",
                "items", true,
                "contains", Map.of("type", "string"),
                "minContains", 2
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void acceptsArrayContainsWhenSourcePrefixItemsGuaranteeMatches() {
        Map<String, Object> source = Map.of(
                "type", "array",
                "prefixItems", List.of(
                        Map.of("type", "string", "const", "primary"),
                        Map.of("type", "integer")
                ),
                "items", false,
                "minItems", 1
        );
        Map<String, Object> target = Map.of(
                "type", "array",
                "items", true,
                "contains", Map.of("type", "string", "const", "primary"),
                "minContains", 1
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void acceptsArrayContainsMaxWhenSourceItemsCannotMatch() {
        Map<String, Object> source = Map.of(
                "type", "array",
                "items", Map.of("type", "integer"),
                "maxItems", 5
        );
        Map<String, Object> target = Map.of(
                "type", "array",
                "items", true,
                "contains", Map.of("type", "string"),
                "minContains", 0,
                "maxContains", 0
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void rejectsSourceResidualItemsWhenTargetForbidsUnevaluatedItems() {
        Map<String, Object> source = Map.of(
                "type", "array",
                "prefixItems", List.of(Map.of("type", "string")),
                "items", Map.of("type", "string")
        );
        Map<String, Object> target = Map.of(
                "type", "array",
                "prefixItems", List.of(Map.of("type", "string")),
                "unevaluatedItems", false
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("target unevaluatedItems=false")
                        .contains("source may produce items beyond prefixItems[1]"));
    }

    @Test
    void acceptsSourceResidualSchemaThatCanFeedTargetUnevaluatedItems() {
        Map<String, Object> source = Map.of(
                "type", "array",
                "prefixItems", List.of(Map.of("type", "string")),
                "unevaluatedItems", Map.of("type", "integer")
        );
        Map<String, Object> target = Map.of(
                "type", "array",
                "prefixItems", List.of(Map.of("type", "string")),
                "unevaluatedItems", Map.of("type", "number")
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void rejectsSourceResidualSchemaThatCannotFeedTargetUnevaluatedItems() {
        Map<String, Object> source = Map.of(
                "type", "array",
                "prefixItems", List.of(Map.of("type", "string")),
                "unevaluatedItems", Map.of("type", "string")
        );
        Map<String, Object> target = Map.of(
                "type", "array",
                "prefixItems", List.of(Map.of("type", "string")),
                "unevaluatedItems", Map.of("type", "integer")
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("unevaluatedItems")
                        .contains("source type string cannot feed target type integer"));
    }

    @Test
    void acceptsTargetConditionalThenBranchWhenSourceProvesCondition() {
        Map<String, Object> source = paymentSourceSchema("CARD", "cardNumber");

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, conditionalPaymentSchema())).isEmpty();
    }

    @Test
    void acceptsTargetConditionalElseBranchWhenSourceIsDisjointFromCondition() {
        Map<String, Object> source = paymentSourceSchema("BANK", "bankAccount");

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, conditionalPaymentSchema())).isEmpty();
    }

    @Test
    void rejectsTargetConditionalWhenSourceMayEnterThenWithoutRequiredField() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "object");
        source.put("properties", Map.of(
                "paymentMethod", Map.of("type", "string", "enum", List.of("CARD", "BANK"))
        ));
        source.put("required", List.of("paymentMethod"));
        source.put("additionalProperties", false);

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, conditionalPaymentSchema()))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("target conditional then may apply")
                        .contains("cardNumber"));
    }

    @Test
    void rejectsSourceResidualFieldThatCanCollideWithTargetOptionalProperty() {
        Map<String, Object> source = Map.of(
                "type", "object",
                "additionalProperties", Map.of("type", "string")
        );
        Map<String, Object> target = Map.of(
                "type", "object",
                "properties", Map.of("score", Map.of("type", "integer")),
                "additionalProperties", true
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("at 'score'")
                        .contains("source type string cannot feed target type integer"));
    }

    @Test
    void rejectsSourcePatternPropertyThatCanCollideWithTargetOptionalProperty() {
        Map<String, Object> source = Map.of(
                "type", "object",
                "patternProperties", Map.of("^score$", Map.of("type", "string")),
                "additionalProperties", false
        );
        Map<String, Object> target = Map.of(
                "type", "object",
                "properties", Map.of("score", Map.of("type", "integer")),
                "additionalProperties", true
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("at 'score'")
                        .contains("source type string cannot feed target type integer"));
    }

    @Test
    void acceptsResidualFieldWhenPropertyNamesExcludeTargetOptionalProperty() {
        Map<String, Object> source = Map.of(
                "type", "object",
                "propertyNames", Map.of("pattern", "^meta\\."),
                "additionalProperties", Map.of("type", "string")
        );
        Map<String, Object> target = Map.of(
                "type", "object",
                "properties", Map.of("score", Map.of("type", "integer")),
                "additionalProperties", true
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void rejectsUnboundedSourceWhenTargetExcludesFiniteValueWithNot() {
        Map<String, Object> source = Map.of("type", "string");
        Map<String, Object> target = Map.of(
                "type", "string",
                "not", Map.of("const", "ARCHIVED")
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("target excludes value(s) [ARCHIVED]")
                        .contains("source schema could produce them"));
    }

    @Test
    void acceptsFiniteSourceEnumThatAvoidsTargetNotExclusion() {
        Map<String, Object> source = Map.of("type", "string", "enum", List.of("ACTIVE", "PENDING"));
        Map<String, Object> target = Map.of(
                "type", "string",
                "not", Map.of("const", "ARCHIVED")
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void rejectsFiniteSourceEnumThatContainsTargetNotExclusion() {
        Map<String, Object> source = Map.of("type", "string", "enum", List.of("ACTIVE", "ARCHIVED"));
        Map<String, Object> target = Map.of(
                "type", "string",
                "not", Map.of("const", "ARCHIVED")
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("source enum value(s) [ARCHIVED]")
                        .contains("do not match target schema string"));
    }

    @Test
    void acceptsSourceConstWhenItMatchesTargetConst() {
        Map<String, Object> source = Map.of("type", "string", "const", "APPROVE");
        Map<String, Object> target = Map.of("type", "string", "const", "APPROVE");

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void rejectsUnboundedSourceWhenTargetRequiresConst() {
        Map<String, Object> source = Map.of("type", "string");
        Map<String, Object> target = Map.of("type", "string", "const", "APPROVE");

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("target const [APPROVE]")
                        .contains("requires a finite source value domain"));
    }

    @Test
    void rejectsSourceConstWhenItDiffersFromTargetConst() {
        Map<String, Object> source = Map.of("type", "string", "const", "REJECT");
        Map<String, Object> target = Map.of("type", "string", "const", "APPROVE");

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("source const value(s) [REJECT]")
                        .contains("outside target const [APPROVE]"));
    }

    @Test
    void acceptsNumericallyEquivalentFiniteDomains() {
        Map<String, Object> source = Map.of("type", "number", "enum", List.of(1));
        Map<String, Object> target = Map.of("type", "number", "const", 1.0);

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void rejectsNumericallyEquivalentValueExcludedByTargetNot() {
        Map<String, Object> source = Map.of("type", "number", "enum", List.of(1));
        Map<String, Object> target = Map.of(
                "type", "number",
                "not", Map.of("const", 1.0)
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("source enum value(s) [1]")
                        .contains("do not match target schema number"));
    }

    @Test
    void rejectsFiniteSourceEnumWhenTargetNotPatternExcludesValue() {
        Map<String, Object> source = Map.of("type", "string", "enum", List.of("ACTIVE", "ARCHIVED"));
        Map<String, Object> target = Map.of(
                "type", "string",
                "not", Map.of("pattern", "^ARCHIVED$")
        );

        assertThat(VisualSchemaCompatibility.valueMatchesSchema("ARCHIVED", target)).isFalse();
        assertThat(VisualSchemaCompatibility.valueMatchesSchema("ACTIVE", target)).isTrue();
        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("source enum value(s) [ARCHIVED]")
                        .contains("do not match target schema string"));
    }

    @Test
    void acceptsFiniteSourceEnumWhenTargetNotPatternDoesNotMatch() {
        Map<String, Object> source = Map.of("type", "string", "enum", List.of("ACTIVE", "PENDING"));
        Map<String, Object> target = Map.of(
                "type", "string",
                "not", Map.of("pattern", "^ARCHIVED$")
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void rejectsUnboundedSourceWhenTargetNotPatternCouldMatch() {
        Map<String, Object> source = Map.of("type", "string");
        Map<String, Object> target = Map.of(
                "type", "string",
                "not", Map.of("pattern", "^ARCHIVED$")
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("target excludes schema string pattern '^ARCHIVED$'")
                        .contains("source string cannot prove it avoids the excluded domain"));
    }

    @Test
    void acceptsSourceWhenTargetOnlyNotSchemaIsDisjoint() {
        Map<String, Object> source = Map.of("type", "integer");
        Map<String, Object> target = Map.of("not", Map.of("type", "string"));

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void rejectsSourceWhenTargetOnlyNotSchemaCouldExcludeIt() {
        Map<String, Object> source = Map.of("type", "string");
        Map<String, Object> target = Map.of("not", Map.of("type", "string"));

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("target excludes schema string")
                        .contains("source string cannot prove it avoids the excluded domain"));
    }

    @Test
    void acceptsSourceNumericRangeThatAvoidsTargetNotMinimum() {
        Map<String, Object> source = Map.of("type", "number", "maximum", -1);
        Map<String, Object> target = Map.of(
                "type", "number",
                "not", Map.of("minimum", 0)
        );

        assertThat(VisualSchemaCompatibility.valueMatchesSchema(-1, target)).isTrue();
        assertThat(VisualSchemaCompatibility.valueMatchesSchema(0, target)).isFalse();
        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void acceptsSourceExclusiveNumericRangeThatAvoidsTargetNotMinimumAtSameBoundary() {
        Map<String, Object> source = Map.of("type", "number", "exclusiveMaximum", 0);
        Map<String, Object> target = Map.of(
                "type", "number",
                "not", Map.of("minimum", 0)
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void rejectsSourceNumericRangeWhenItOverlapsTargetNotMinimum() {
        Map<String, Object> source = Map.of("type", "number", "maximum", 10);
        Map<String, Object> target = Map.of(
                "type", "number",
                "not", Map.of("minimum", 0)
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("target excludes schema number value >= 0")
                        .contains("source number cannot prove it avoids the excluded domain"));
    }

    @Test
    void acceptsSourceStringLengthThatAvoidsTargetNotMaxLength() {
        Map<String, Object> source = Map.of("type", "string", "minLength", 4);
        Map<String, Object> target = Map.of(
                "type", "string",
                "not", Map.of("maxLength", 3)
        );

        assertThat(VisualSchemaCompatibility.valueMatchesSchema("ABCD", target)).isTrue();
        assertThat(VisualSchemaCompatibility.valueMatchesSchema("ABC", target)).isFalse();
        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void treatsTypeLessNumericNotAsNumericExclusionForValueMatching() {
        Map<String, Object> source = Map.of("type", "string");
        Map<String, Object> target = Map.of(
                "type", "string",
                "not", Map.of("minimum", 0)
        );

        assertThat(VisualSchemaCompatibility.valueMatchesSchema("ACTIVE", target)).isTrue();
        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void rejectsSourceStringLengthWhenItCouldMatchTargetNotMaxLength() {
        Map<String, Object> source = Map.of("type", "string", "minLength", 2);
        Map<String, Object> target = Map.of(
                "type", "string",
                "not", Map.of("maxLength", 3)
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("target excludes schema string maxLength 3")
                        .contains("source string cannot prove it avoids the excluded domain"));
    }

    @Test
    void acceptsSourceArraySizeThatAvoidsTargetNotMinItems() {
        Map<String, Object> source = Map.of("type", "array", "maxItems", 1);
        Map<String, Object> target = Map.of(
                "type", "array",
                "not", Map.of("minItems", 2)
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void acceptsArrayItemsThatAvoidTargetNotContains() {
        Map<String, Object> source = Map.of(
                "type", "array",
                "items", Map.of("type", "string", "enum", List.of("GOOD", "OK"))
        );
        Map<String, Object> target = Map.of(
                "type", "array",
                "not", Map.of("contains", Map.of("const", "BAD"))
        );

        assertThat(VisualSchemaCompatibility.valueMatchesSchema(List.of("GOOD"), target)).isTrue();
        assertThat(VisualSchemaCompatibility.valueMatchesSchema(List.of("BAD"), target)).isFalse();
        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void acceptsBoundedPrefixItemsThatAvoidTargetNotContains() {
        Map<String, Object> source = Map.of(
                "type", "array",
                "prefixItems", List.of(Map.of("type", "integer", "maximum", 0)),
                "maxItems", 1
        );
        Map<String, Object> target = Map.of(
                "type", "array",
                "not", Map.of("contains", Map.of("minimum", 1))
        );

        assertThat(VisualSchemaCompatibility.valueMatchesSchema(List.of(0), target)).isTrue();
        assertThat(VisualSchemaCompatibility.valueMatchesSchema(List.of(1), target)).isFalse();
        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void rejectsArrayPrefixItemsWhenAdditionalItemsCouldMatchTargetNotContains() {
        Map<String, Object> source = Map.of(
                "type", "array",
                "prefixItems", List.of(Map.of("type", "string", "enum", List.of("GOOD")))
        );
        Map<String, Object> target = Map.of(
                "type", "array",
                "not", Map.of("contains", Map.of("const", "BAD"))
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("target excludes schema array contains [BAD] minContains 1")
                        .contains("source array cannot prove it avoids the excluded domain"));
    }

    @Test
    void acceptsSourceObjectSizeThatAvoidsTargetNotMinProperties() {
        Map<String, Object> source = Map.of("type", "object", "maxProperties", 1);
        Map<String, Object> target = Map.of(
                "type", "object",
                "not", Map.of("minProperties", 2)
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void acceptsObjectMinPropertiesWhenSourceRequiredFieldsGuaranteeCount() {
        Map<String, Object> source = Map.of(
                "type", "object",
                "properties", Map.of(
                        "customerId", Map.of("type", "string"),
                        "score", Map.of("type", "integer")
                ),
                "required", List.of("customerId", "score")
        );
        Map<String, Object> target = Map.of(
                "type", "object",
                "minProperties", 2
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void rejectsObjectMinPropertiesWhenSourceRequiredFieldsAreTooFew() {
        Map<String, Object> source = Map.of(
                "type", "object",
                "properties", Map.of(
                        "customerId", Map.of("type", "string")
                ),
                "required", List.of("customerId")
        );
        Map<String, Object> target = Map.of(
                "type", "object",
                "minProperties", 2
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("source minProperties 1 is weaker than target minProperties 2"));
    }

    @Test
    void acceptsSourceDependentRequiredWhenTargetDependentSchemaRequiresSameField() {
        Map<String, Object> source = Map.of(
                "type", "object",
                "properties", Map.of(
                        "creditCard", Map.of("type", "string"),
                        "billingAddress", Map.of("type", "string")
                ),
                "dependentRequired", Map.of("creditCard", List.of("billingAddress")),
                "additionalProperties", false
        );
        Map<String, Object> target = Map.of(
                "type", "object",
                "properties", Map.of(
                        "creditCard", Map.of("type", "string"),
                        "billingAddress", Map.of("type", "string")
                ),
                "dependentSchemas", Map.of(
                        "creditCard", Map.of(
                                "properties", Map.of("billingAddress", Map.of("type", "string")),
                                "required", List.of("billingAddress")
                        )
                ),
                "additionalProperties", false
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void rejectsSourceDependentRequiredWhenDependentFieldCannotSatisfyTargetDependentSchema() {
        Map<String, Object> source = Map.of(
                "type", "object",
                "properties", Map.of(
                        "creditCard", Map.of("type", "string"),
                        "billingAddress", Map.of("type", "integer")
                ),
                "dependentRequired", Map.of("creditCard", List.of("billingAddress")),
                "additionalProperties", false
        );
        Map<String, Object> target = Map.of(
                "type", "object",
                "properties", Map.of(
                        "creditCard", Map.of("type", "string")
                ),
                "dependentSchemas", Map.of(
                        "creditCard", Map.of(
                                "properties", Map.of("billingAddress", Map.of("type", "string")),
                                "required", List.of("billingAddress")
                        )
                ),
                "additionalProperties", true
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("target requires dependentSchemas 'creditCard'")
                        .contains("source does not guarantee the dependent schema"));
    }

    @Test
    void acceptsSourceDependentSchemaWhenItProvesTargetDependentSchema() {
        Map<String, Object> source = Map.of(
                "type", "object",
                "properties", Map.of(
                        "paymentMethod", Map.of("type", "string"),
                        "cardNumber", Map.of("type", "string", "minLength", 8)
                ),
                "dependentSchemas", Map.of(
                        "paymentMethod", Map.of(
                                "properties", Map.of("cardNumber", Map.of("type", "string", "minLength", 8)),
                                "required", List.of("cardNumber")
                        )
                ),
                "additionalProperties", false
        );
        Map<String, Object> target = Map.of(
                "type", "object",
                "properties", Map.of(
                        "paymentMethod", Map.of("type", "string"),
                        "cardNumber", Map.of("type", "string", "minLength", 4)
                ),
                "dependentSchemas", Map.of(
                        "paymentMethod", Map.of(
                                "properties", Map.of("cardNumber", Map.of("type", "string", "minLength", 4)),
                                "required", List.of("cardNumber")
                        )
                ),
                "additionalProperties", false
        );

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void acceptsClosedObjectSourceThatCannotContainTargetNotRequiredProperty() {
        Map<String, Object> source = Map.of(
                "type", "object",
                "properties", Map.of("publicId", Map.of("type", "string")),
                "required", List.of("publicId"),
                "additionalProperties", false
        );
        Map<String, Object> target = Map.of(
                "type", "object",
                "not", Map.of("required", List.of("debug"))
        );

        assertThat(VisualSchemaCompatibility.valueMatchesSchema(Map.of("publicId", "P-1"), target)).isTrue();
        assertThat(VisualSchemaCompatibility.valueMatchesSchema(Map.of("debug", true), target)).isFalse();
        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void acceptsObjectSourcePropertySchemaThatAvoidsTargetNotRequiredConstProperty() {
        Map<String, Object> source = Map.of(
                "type", "object",
                "properties", Map.of("mode", Map.of(
                        "type", "string",
                        "enum", List.of("user", "guest")
                )),
                "additionalProperties", false
        );
        Map<String, Object> target = Map.of(
                "type", "object",
                "not", Map.of(
                        "required", List.of("mode"),
                        "properties", Map.of("mode", Map.of("const", "admin"))
                )
        );

        assertThat(VisualSchemaCompatibility.valueMatchesSchema(Map.of("mode", "user"), target)).isTrue();
        assertThat(VisualSchemaCompatibility.valueMatchesSchema(Map.of("mode", "admin"), target)).isFalse();
        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void acceptsObjectSourceRequiredNameThatAvoidsTargetNotPropertyNames() {
        Map<String, Object> source = Map.of(
                "type", "object",
                "properties", Map.of("public.id", Map.of("type", "string")),
                "required", List.of("public.id"),
                "additionalProperties", false
        );
        Map<String, Object> target = Map.of(
                "type", "object",
                "not", Map.of("propertyNames", Map.of("pattern", "^internal\\."))
        );

        assertThat(VisualSchemaCompatibility.valueMatchesSchema(Map.of("public.id", "P-1"), target)).isTrue();
        assertThat(VisualSchemaCompatibility.valueMatchesSchema(Map.of("internal.id", "I-1"), target)).isFalse();
        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void matchesObjectFiniteValuesByStructureAndNestedNumericValue() {
        Map<String, Object> value = Map.of("a", "x", "b", List.of(1));
        Map<String, Object> schema = Map.of(
                "type", "object",
                "const", Map.of("b", List.of(1.0), "a", "x")
        );

        assertThat(VisualSchemaCompatibility.valueMatchesSchema(value, schema)).isTrue();
    }

    @Test
    void rejectsUnboundedNumberSourceWhenTargetRequiresInteger() {
        Map<String, Object> source = Map.of("type", "number");
        Map<String, Object> target = Map.of("type", "integer");

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("target type integer requires integer-valued source")
                        .contains("source type number has no integral multipleOf"));
    }

    @Test
    void acceptsNumberSourceWithIntegralMultipleOfWhenTargetRequiresInteger() {
        Map<String, Object> source = Map.of("type", "number", "multipleOf", 1);
        Map<String, Object> target = Map.of("type", "integer");

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target)).isEmpty();
    }

    @Test
    void rejectsNumberSourceWithFractionalMultipleOfWhenTargetRequiresInteger() {
        Map<String, Object> source = Map.of("type", "number", "multipleOf", 0.5);
        Map<String, Object> target = Map.of("type", "integer");

        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(source, target))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("source multipleOf 0.5")
                        .contains("does not guarantee integer values"));
    }

    private static Map<String, Object> conditionalPaymentSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "paymentMethod", Map.of("type", "string", "enum", List.of("CARD", "BANK")),
                "cardNumber", Map.of("type", "string"),
                "bankAccount", Map.of("type", "string")
        ));
        schema.put("required", List.of("paymentMethod"));
        schema.put("additionalProperties", false);
        schema.put("if", Map.of(
                "properties", Map.of("paymentMethod", Map.of("const", "CARD")),
                "required", List.of("paymentMethod")
        ));
        schema.put("then", Map.of("required", List.of("cardNumber")));
        schema.put("else", Map.of("required", List.of("bankAccount")));
        return schema;
    }

    private static Map<String, Object> tupleSchemaWithUnevaluatedItems(Object unevaluatedItems) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("prefixItems", List.of(
                Map.of("type", "string"),
                Map.of("type", "integer")
        ));
        schema.put("unevaluatedItems", unevaluatedItems);
        return schema;
    }

    private static Map<String, Object> tupleSchemaWithItems(Object items) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("prefixItems", List.of(
                Map.of("type", "string"),
                Map.of("type", "integer")
        ));
        schema.put("items", items);
        return schema;
    }

    private static Map<String, Object> paymentSourceSchema(String method, String requiredField) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "object");
        source.put("properties", Map.of(
                "paymentMethod", Map.of("type", "string", "const", method),
                requiredField, Map.of("type", "string")
        ));
        source.put("required", List.of("paymentMethod", requiredField));
        source.put("additionalProperties", false);
        return source;
    }
}
