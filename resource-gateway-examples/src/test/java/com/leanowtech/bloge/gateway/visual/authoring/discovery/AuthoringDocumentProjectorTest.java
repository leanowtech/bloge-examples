package com.leanowtech.bloge.gateway.visual.authoring.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringDocumentProjectorTest {

    private final AuthoringDocumentProjector projector =
            new AuthoringDocumentProjector(new ObjectMapper());

    @Test
    void projectsSupportedJsonSchemaAndCallableSignaturesIntoStructuredAuthoring() {
        SchemaEnvelope request = SchemaEnvelope.object(
                Map.of(
                        "customerId", Map.of("type", "string", "minLength", 1),
                        "priority", Map.of("enum", List.of("P0", "P1"))),
                List.of("customerId"));
        OperatorDefinition operator = operator("support:classify", request);
        OperatorLibrary.BuiltInFunction function =
                FrameworkFunctionInventoryTest.functionContract("normalize", "string");

        AuthoringDocumentProjector.Result result = projector.project(library(
                List.of(operator), List.of(function)));

        JsonNode input = result.document().operators()
                .get("support:classify").input().get("request");
        assertThat(input.path("fields").path("customerId").path("type").asText())
                .isEqualTo("string");
        assertThat(input.path("fields").path("customerId").path("minLength").asInt())
                .isEqualTo(1);
        assertThat(input.path("fields").has("priority?")).isTrue();
        assertThat(result.document().functions().get("normalize").signatures())
                .containsExactly("(value: string) -> string");
        assertThat(result.reviewItems())
                .singleElement()
                .satisfies(review -> {
                    assertThat(review.code())
                            .isEqualTo("RG.AUTHORING.DISCOVERY_SCHEMA_REVIEW_REQUIRED");
                    assertThat(review.action()).contains("config");
                });
    }

    @Test
    void convertsUnsupportedSchemaToUnknownAndRequiresExplicitReview() {
        SchemaEnvelope union = new SchemaEnvelope(
                SchemaEnvelope.JSON_SCHEMA,
                "2020-12",
                Map.of("oneOf", List.of(
                        Map.of("type", "string"),
                        Map.of("type", "integer"))));

        AuthoringDocumentProjector.Result result = projector.project(
                library(List.of(operator("support:classify", union)), List.of()));

        assertThat(result.document().operators()
                .get("support:classify").input().get("request").asText())
                .isEqualTo("unknown");
        assertThat(result.reviewItems())
                .hasSize(2)
                .allSatisfy(review -> assertThat(review.code())
                        .isEqualTo("RG.AUTHORING.DISCOVERY_SCHEMA_REVIEW_REQUIRED"));
    }

    private static OperatorDefinition operator(String ref, SchemaEnvelope input) {
        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                ref,
                "1.0.0",
                new OperatorDefinition.Display("Classify", "", List.of("support")),
                OperatorDefinition.Source.builtIn("java-operator"),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("request", input, true, "")),
                        List.of(new OperatorDefinition.Port(
                                "result",
                                SchemaEnvelope.object(
                                        Map.of("category", Map.of("type", "string")),
                                        List.of("category")),
                                true,
                                ""))),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", ref, Map.of()),
                List.of());
    }

    private static OperatorLibrary library(
            List<OperatorDefinition> operators,
            List<OperatorLibrary.BuiltInFunction> functions) {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "discovered-support",
                "Discovered support",
                "1.0.0",
                "support-platform",
                OperatorLibrary.STATUS_ACTIVE,
                functions,
                operators);
    }
}
