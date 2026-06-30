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
}
