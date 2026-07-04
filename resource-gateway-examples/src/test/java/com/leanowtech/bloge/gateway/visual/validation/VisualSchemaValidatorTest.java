package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
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
    void acceptsRuntimeValuesThatMatchEveryAllOfBranch() {
        SchemaEnvelope schema = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "allOf", List.of(
                        Map.of("type", "string"),
                        Map.of("minLength", 3),
                        Map.of("pattern", "^[A-Z]+$")
                )
        ));

        assertThat(VisualSchemaValidator.validateValue(schema, "APPROVE", "/context")).isEmpty();
    }

    @Test
    void validatesTypelessRequiredSchemaAsObject() {
        Map<String, Object> schema = Map.of(
                "required", List.of("customerId")
        );

        assertThat(VisualSchemaValidator.validateSchema(schema, "/schema")).isEmpty();
        assertThat(VisualSchemaValidator.validateValue(
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema),
                Map.of("customerId", "u1"),
                "/context"
        )).isEmpty();

        var missingRequired = VisualSchemaValidator.validateValue(
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema),
                Map.of("tenantId", "t1"),
                "/context"
        );
        assertThat(missingRequired)
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.context.requiredMissing");
                    assertThat(diagnostic.target()).isEqualTo("/context/customerId");
                });

        var wrongType = VisualSchemaValidator.validateValue(
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema),
                "u1",
                "/context"
        );
        assertThat(wrongType)
                .singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.context.typeMismatch"));
    }

    @Test
    void validatesTypelessContainsSchemaAsArray() {
        Map<String, Object> schema = Map.of(
                "contains", Map.of("type", "integer"),
                "minContains", 1
        );

        assertThat(VisualSchemaValidator.validateSchema(schema, "/schema")).isEmpty();
        assertThat(VisualSchemaValidator.validateValue(
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema),
                List.of("risk", 7),
                "/context"
        )).isEmpty();

        var diagnostics = VisualSchemaValidator.validateValue(
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema),
                List.of("risk"),
                "/context"
        );
        assertThat(diagnostics)
                .singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.context.arrayConstraintMismatch"));
    }

    @Test
    void acceptsConditionalSchemasWithRequiredOnlyBranches() {
        Map<String, Object> schema = conditionalPaymentSchema();

        assertThat(VisualSchemaValidator.validateSchema(schema, "/schema")).isEmpty();
        assertThat(VisualSchemaValidator.validateValue(
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema),
                Map.of("paymentMethod", "CARD", "cardNumber", "41111111"),
                "/context"
        )).isEmpty();
        assertThat(VisualSchemaValidator.validateValue(
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema),
                Map.of("paymentMethod", "BANK", "bankAccount", "BA-1"),
                "/context"
        )).isEmpty();
    }

    @Test
    void reportsRuntimeConditionalBranchMismatches() {
        SchemaEnvelope schema = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", conditionalPaymentSchema());

        var diagnostics = VisualSchemaValidator.validateValue(schema, Map.of(
                "paymentMethod", "CARD",
                "bankAccount", "BA-1"
        ), "/context");

        assertThat(diagnostics)
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.context.conditionalMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/context");
                    assertThat(diagnostic.message()).contains("then branch");
                });
    }

    @Test
    void rejectsDefaultValuesThatViolateConditionalBranches() {
        Map<String, Object> schema = new LinkedHashMap<>(conditionalPaymentSchema());
        schema.put("default", Map.of(
                "paymentMethod", "CARD",
                "bankAccount", "BA-1"
        ));

        assertThat(VisualSchemaValidator.validateSchema(schema, "/schema"))
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.defaultConditionalMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/schema/default");
                });
    }

    @Test
    void rejectsRuntimeValuesThatMissAllOfBranchWithSpecificConstraintDiagnostic() {
        SchemaEnvelope schema = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "allOf", List.of(
                        Map.of("type", "string"),
                        Map.of("minLength", 3),
                        Map.of("pattern", "^[A-Z]+$")
                )
        ));

        var diagnostics = VisualSchemaValidator.validateValue(schema, "no", "/context");

        assertThat(diagnostics)
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.context.stringConstraintMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/context");
                    assertThat(diagnostic.message()).contains("length");
                });
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
    void acceptsSchemaFormNotPatternAndRejectsRuntimeValueThatMatchesIt() {
        SchemaEnvelope schema = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "properties", Map.of(
                        "decision", Map.of(
                                "type", "string",
                                "not", Map.of("pattern", "^ARCHIVED$")
                        )
                ),
                "required", List.of("decision"),
                "additionalProperties", false
        ));

        assertThat(VisualSchemaValidator.validateSchema(schema.schema(), "/schema")).isEmpty();
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
                    assertThat(diagnostic.message()).contains("pattern=^ARCHIVED$");
                });
    }

    @Test
    void rejectsDefaultAndConstValuesThatMatchSchemaFormNotPattern() {
        var diagnostics = VisualSchemaValidator.validateSchema(Map.of(
                "type", "string",
                "not", Map.of("pattern", "^ARCHIVED$"),
                "default", "ARCHIVED",
                "const", "ARCHIVED"
        ), "/schema");

        assertThat(diagnostics)
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.defaultNotMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/schema/default");
                })
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.constConstraintMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/schema/const");
                    assertThat(diagnostic.message()).contains("not exclusion");
                });
    }

    @Test
    void rejectsInvalidSchemaFormNotPattern() {
        var diagnostics = VisualSchemaValidator.validateSchema(Map.of(
                "type", "string",
                "not", Map.of("pattern", "[")
        ), "/schema");

        assertThat(diagnostics)
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.patternConstraintInvalid");
                    assertThat(diagnostic.target()).isEqualTo("/schema/not/pattern");
                });
    }

    @Test
    void treatsTypeLessNumericNotAsNumericRuntimeExclusion() {
        SchemaEnvelope schema = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "properties", Map.of(
                        "decision", Map.of(
                                "type", "string",
                                "not", Map.of("minimum", 0)
                        )
                ),
                "required", List.of("decision"),
                "additionalProperties", false
        ));

        assertThat(VisualSchemaValidator.validateSchema(schema.schema(), "/schema")).isEmpty();
        assertThat(VisualSchemaValidator.validateValue(schema, Map.of(
                "decision", "ACTIVE"
        ), "/context")).isEmpty();
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
    void acceptsTupleSchemasThatForbidUnevaluatedItems() {
        Map<String, Object> schema = Map.of(
                "type", "array",
                "prefixItems", List.of(
                        Map.of("type", "string"),
                        Map.of("type", "integer")
                ),
                "unevaluatedItems", false,
                "default", List.of("customerId", 7)
        );

        assertThat(VisualSchemaValidator.validateSchema(schema, "/schema")).isEmpty();
        assertThat(VisualSchemaValidator.validateValue(
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema),
                List.of("customerId", 7),
                "/context"
        )).isEmpty();

        var diagnostics = VisualSchemaValidator.validateValue(
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema),
                List.of("customerId", 7, "extra"),
                "/context"
        );

        assertThat(diagnostics)
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.context.arrayConstraintMismatch");
                    assertThat(diagnostic.message()).contains("unevaluatedItems");
                });
    }

    @Test
    void acceptsTupleSchemasThatForbidAdditionalItemsWithBooleanItems() {
        Map<String, Object> schema = Map.of(
                "type", "array",
                "prefixItems", List.of(
                        Map.of("type", "string"),
                        Map.of("type", "integer")
                ),
                "items", false,
                "default", List.of("customerId", 7)
        );

        assertThat(VisualSchemaValidator.validateSchema(schema, "/schema")).isEmpty();
        assertThat(VisualSchemaValidator.validateValue(
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema),
                List.of("customerId", 7),
                "/context"
        )).isEmpty();

        var diagnostics = VisualSchemaValidator.validateValue(
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema),
                List.of("customerId", 7, "extra"),
                "/context"
        );

        assertThat(diagnostics)
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.context.arrayConstraintMismatch");
                    assertThat(diagnostic.message()).contains("items");
                });
    }

    @Test
    void validatesBooleanItemsPolicyForDefaults() {
        Map<String, Object> schema = Map.of(
                "type", "array",
                "prefixItems", List.of(Map.of("type", "string")),
                "items", false,
                "default", List.of("risk", "extra")
        );

        assertThat(VisualSchemaValidator.validateSchema(schema, "/schema"))
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.defaultConstraintMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/schema/default");
                    assertThat(diagnostic.message()).contains("items");
                });
    }

    @Test
    void validatesUnevaluatedItemsSchemaForDefaults() {
        Map<String, Object> schema = Map.of(
                "type", "array",
                "prefixItems", List.of(Map.of("type", "string")),
                "unevaluatedItems", Map.of("type", "integer"),
                "default", List.of("risk", "bad")
        );

        assertThat(VisualSchemaValidator.validateSchema(schema, "/schema"))
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.defaultConstraintMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/schema/default");
                    assertThat(diagnostic.message()).contains("unevaluatedItems");
                });
    }

    @Test
    void rejectsInvalidUnevaluatedItemsShapes() {
        var diagnostics = VisualSchemaValidator.validateSchema(Map.of(
                "type", "array",
                "prefixItems", List.of(Map.of("type", "string")),
                "unevaluatedItems", List.of("bad")
        ), "/schema");

        assertThat(diagnostics)
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.unevaluatedItemsInvalid");
                    assertThat(diagnostic.target()).isEqualTo("/schema/unevaluatedItems");
                });
    }

    @Test
    void rejectsInvalidAllOfSchemaShapes() {
        var diagnostics = VisualSchemaValidator.validateSchema(Map.of(
                "type", "object",
                "properties", Map.of(
                        "decision", Map.of("allOf", List.of()),
                        "riskSignal", Map.of("allOf", List.of("bad"))
                )
        ), "/schema");

        assertThat(diagnostics)
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.allOfInvalid");
                    assertThat(diagnostic.target()).isEqualTo("/schema/properties/decision/allOf");
                })
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.allOfInvalid");
                    assertThat(diagnostic.target()).isEqualTo("/schema/properties/riskSignal/allOf/0");
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
                        "components", Map.of("$ref", "#/components/schemas/Risk"),
                        "plainFragment", Map.of("$ref", "#Risk")
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
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.refUnresolved");
                    assertThat(diagnostic.message()).contains("#/components/schemas/Risk");
                    assertThat(diagnostic.target()).isEqualTo("/schema/properties/components/$ref");
                })
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.refUnsupported");
                    assertThat(diagnostic.message()).contains("#Risk");
                    assertThat(diagnostic.target()).isEqualTo("/schema/properties/plainFragment/$ref");
                });
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
}
