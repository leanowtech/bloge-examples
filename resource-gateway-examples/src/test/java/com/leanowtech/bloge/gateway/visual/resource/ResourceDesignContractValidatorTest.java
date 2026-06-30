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
