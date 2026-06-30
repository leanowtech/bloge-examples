package com.leanowtech.bloge.gateway.visual.resource;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for resource design contract validation.
 */
class ResourceDesignContractValidatorTest {

    private final ResourceDesignContractValidator validator = new ResourceDesignContractValidator();

    @Test
    void acceptsValidContractWithArrayItems() {
        VisualValidationResult result = validator.validate(validContract(Map.of()));

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsNumericBoundsInResourceContractSchemas() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                SchemaEnvelope.object(Map.of(
                        "minScore", Map.of("type", "integer", "minimum", 0, "maximum", 850)
                ), List.of("minScore")),
                SchemaEnvelope.object(Map.of(
                        "score", Map.of("type", "integer", "minimum", 0, "maximum", 850)
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void rejectsArraySchemaWithoutItems() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                requestSchema(),
                SchemaEnvelope.object(Map.of(
                        "items", Map.of("type", "array")
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.arrayItemsMissing");
                    assertThat(diagnostic.target()).isEqualTo("/responseSchema/schema/properties/items/items");
                });
    }

    @Test
    void rejectsUnsupportedJsonSchemaKeywords() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                SchemaEnvelope.object(Map.of(
                        "userId", Map.of("$ref", "#/$defs/UserId")
                ), List.of()),
                SchemaEnvelope.object(Map.of(
                        "customerCode", Map.of("type", "string", "pattern", "^[A-Z]+$"),
                        "decision", Map.of("oneOf", List.of(
                                Map.of("type", "string"),
                                Map.of("type", "integer")
                        ))
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.refUnsupported",
                        "visual.schema.constraintUnsupported",
                        "visual.schema.compositionUnsupported"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/requestSchema/schema/properties/userId/$ref",
                        "/responseSchema/schema/properties/customerCode/pattern",
                        "/responseSchema/schema/properties/decision/oneOf"
                );
    }

    @Test
    void rejectsUnsupportedSchemaEnvelope() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                new SchemaEnvelope("json-schema", "draft-07", Map.of("type", "object")),
                new SchemaEnvelope("avro", "2020-12", Map.of("type", "object")),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.versionUnsupported",
                        "visual.schema.formatUnsupported"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/requestSchema/version",
                        "/responseSchema/format"
                );
    }

    @Test
    void rejectsRawSecretExamples() {
        ResourceDesignContract contract = validContract(Map.of(
                "request", Map.of("apiKey", "sk-123456789012")
        ));

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.secret.raw");
                    assertThat(diagnostic.target()).isEqualTo("/examples/request/apiKey");
                });
    }

    private static ResourceDesignContract validContract(Map<String, Object> examples) {
        return new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                requestSchema(),
                SchemaEnvelope.object(Map.of(
                        "items", Map.of(
                                "type", "array",
                                "items", Map.of("type", "object", "additionalProperties", true)
                        )
                ), List.of()),
                examples,
                "ACTIVE"
        );
    }

    private static SchemaEnvelope requestSchema() {
        return SchemaEnvelope.object(Map.of(
                "userId", Map.of("type", "string")
        ), List.of("userId"));
    }
}
