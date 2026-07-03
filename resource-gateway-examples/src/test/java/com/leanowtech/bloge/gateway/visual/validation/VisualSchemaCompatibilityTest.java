package com.leanowtech.bloge.gateway.visual.validation;

import org.junit.jupiter.api.Test;

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
}
