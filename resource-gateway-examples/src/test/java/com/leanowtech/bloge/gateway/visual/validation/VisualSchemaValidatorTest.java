package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for visual schema value validation.
 */
class VisualSchemaValidatorTest {

    @Test
    void reportsRuntimeValueDiagnosticsAtPreciseSchemaPaths() {
        SchemaEnvelope schema = SchemaEnvelope.object(Map.of(
                "customer", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "id", Map.of("type", "string"),
                                "tier", Map.of("type", "string", "enum", List.of("gold", "silver"))
                        ),
                        "required", List.of("id", "tier"),
                        "additionalProperties", false
                ),
                "scores", Map.of(
                        "type", "array",
                        "items", Map.of("type", "integer")
                )
        ), List.of("customer", "scores"));

        var diagnostics = VisualSchemaValidator.validateValue(schema, Map.of(
                "customer", Map.of(
                        "tier", "bronze",
                        "extra", true
                ),
                "scores", List.of(700, "bad")
        ), "/context");

        assertThat(diagnostics)
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.context.requiredMissing");
                    assertThat(diagnostic.target()).isEqualTo("/context/customer/id");
                })
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.context.enumMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/context/customer/tier");
                })
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.context.unknownProperty");
                    assertThat(diagnostic.target()).isEqualTo("/context/customer/extra");
                })
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.context.typeMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/context/scores/1");
                });
    }

    @Test
    void acceptsRuntimeValuesThatMatchOneOfOrAnyOfBranches() {
        SchemaEnvelope schema = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "properties", Map.of(
                        "decision", Map.of(
                                "oneOf", List.of(
                                        Map.of("type", "string", "enum", List.of("APPROVE", "REJECT")),
                                        Map.of("type", "integer", "minimum", 100)
                                )
                        ),
                        "riskSignal", Map.of(
                                "anyOf", List.of(
                                        Map.of("type", "string", "pattern", "^RISK_"),
                                        Map.of("type", "number", "minimum", 0)
                                )
                        )
                ),
                "required", List.of("decision", "riskSignal"),
                "additionalProperties", false
        ));

        assertThat(VisualSchemaValidator.validateValue(schema, Map.of(
                "decision", 120,
                "riskSignal", "RISK_HIGH"
        ), "/context")).isEmpty();
        assertThat(VisualSchemaValidator.validateValue(schema, Map.of(
                "decision", "APPROVE",
                "riskSignal", 0.35
        ), "/context")).isEmpty();
    }

    @Test
    void reportsRuntimeUnionBranchMismatches() {
        SchemaEnvelope schema = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "properties", Map.of(
                        "decision", Map.of(
                                "oneOf", List.of(
                                        Map.of("type", "integer"),
                                        Map.of("type", "number")
                                )
                        ),
                        "riskSignal", Map.of(
                                "anyOf", List.of(
                                        Map.of("type", "string", "pattern", "^RISK_"),
                                        Map.of("type", "number", "minimum", 0)
                                )
                        )
                ),
                "required", List.of("decision", "riskSignal"),
                "additionalProperties", false
        ));

        var diagnostics = VisualSchemaValidator.validateValue(schema, Map.of(
                "decision", 42,
                "riskSignal", true
        ), "/context");

        assertThat(diagnostics)
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.context.oneOfMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/context/decision");
                    assertThat(diagnostic.message()).contains("matched 2");
                })
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.context.anyOfMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/context/riskSignal");
                    assertThat(diagnostic.message()).contains("matched none");
                });
    }

    @Test
    void rejectsRuntimeValueThatMatchesFiniteNotExclusion() {
        SchemaEnvelope schema = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "properties", Map.of(
                        "decision", Map.of(
                                "type", "string",
                                "not", Map.of("const", "ARCHIVED")
                        )
                ),
                "required", List.of("decision"),
                "additionalProperties", false
        ));

        assertThat(VisualSchemaValidator.validateValue(schema, Map.of(
                "decision", "ACTIVE"
        ), "/context")).isEmpty();

        var diagnostics = VisualSchemaValidator.validateValue(schema, Map.of(
                "decision", "ARCHIVED"
        ), "/context");

        assertThat(diagnostics)
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.context.notMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/context/decision");
                    assertThat(diagnostic.message()).contains("ARCHIVED");
                });
    }

    @Test
    void acceptsRuntimeAndDefaultValuesWithNumericallyEquivalentFiniteDomains() {
        SchemaEnvelope schema = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "properties", Map.of(
                        "amount", Map.of(
                                "type", "number",
                                "const", 1.0
                        )
                ),
                "required", List.of("amount"),
                "additionalProperties", false
        ));

        assertThat(VisualSchemaValidator.validateValue(schema, Map.of("amount", 1), "/context")).isEmpty();
        assertThat(VisualSchemaValidator.validateSchema(Map.of(
                "type", "number",
                "enum", List.of(1.0),
                "default", 1
        ), "/schema")).isEmpty();
    }

    @Test
    void acceptsConstWhenItMatchesEnumBySchemaValueEquality() {
        assertThat(VisualSchemaValidator.validateSchema(Map.of(
                "type", "number",
                "enum", List.of(1.0),
                "const", 1
        ), "/schema")).isEmpty();

        assertThat(VisualSchemaValidator.validateSchema(Map.of(
                "type", "enum",
                "values", List.of(Map.of("status", "APPROVE", "score", 1.0)),
                "const", Map.of("score", 1, "status", "APPROVE")
        ), "/schema")).isEmpty();
    }

    @Test
    void reportsSchemaEnumDuplicatesUsingSchemaValueEquality() {
        Map<String, Object> first = Map.of("a", "x", "b", List.of(1));
        Map<String, Object> second = Map.of("b", List.of(1.0), "a", "x");

        var diagnostics = VisualSchemaValidator.validateSchema(Map.of(
                "type", "object",
                "enum", List.of(first, second)
        ), "/schema");

        assertThat(diagnostics)
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.enumDuplicate");
                    assertThat(diagnostic.target()).isEqualTo("/schema/enum/1");
                });
    }

    @Test
    void rejectsRuntimeUniqueItemsDuplicatesUsingSchemaValueEquality() {
        SchemaEnvelope schema = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "array",
                "uniqueItems", true
        ));

        var diagnostics = VisualSchemaValidator.validateValue(schema, List.of(
                Map.of("a", "x", "b", List.of(1)),
                Map.of("b", List.of(1.0), "a", "x")
        ), "/context");

        assertThat(diagnostics)
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.context.arrayConstraintMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/context");
                    assertThat(diagnostic.message()).contains("uniqueItems");
                });
    }

    @Test
    void rejectsInvalidUnionSchemaShapes() {
        var diagnostics = VisualSchemaValidator.validateSchema(Map.of(
                "type", "object",
                "properties", Map.of(
                        "decision", Map.of("oneOf", List.of()),
                        "riskSignal", Map.of("anyOf", List.of("bad"))
                )
        ), "/schema");

        assertThat(diagnostics)
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.unionInvalid");
                    assertThat(diagnostic.target()).isEqualTo("/schema/properties/decision/oneOf");
                })
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.unionInvalid");
                    assertThat(diagnostic.target()).isEqualTo("/schema/properties/riskSignal/anyOf/0");
                });
    }

    @Test
    void reportsActionableSchemaReferenceDiagnostics() {
        var diagnostics = VisualSchemaValidator.validateSchema(Map.of(
                "type", "object",
                "properties", Map.of(
                        "missingLocal", Map.of("$ref", "#/$defs/Missing"),
                        "remote", Map.of("$ref", "https://schemas.example.test/Risk.json"),
                        "components", Map.of("$ref", "#/components/schemas/Risk")
                )
        ), "/schema");

        assertThat(diagnostics)
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.refUnresolved");
                    assertThat(diagnostic.message()).contains("#/$defs/Missing");
                    assertThat(diagnostic.target()).isEqualTo("/schema/properties/missingLocal/$ref");
                })
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.refRemoteUnsupported");
                    assertThat(diagnostic.message()).contains("https://schemas.example.test/Risk.json");
                    assertThat(diagnostic.target()).isEqualTo("/schema/properties/remote/$ref");
                })
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.refUnsupported");
                    assertThat(diagnostic.message()).contains("#/components/schemas/Risk");
                    assertThat(diagnostic.target()).isEqualTo("/schema/properties/components/$ref");
                });
    }
}
