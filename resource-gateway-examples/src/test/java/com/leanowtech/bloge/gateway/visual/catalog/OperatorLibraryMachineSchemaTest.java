package com.leanowtech.bloge.gateway.visual.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the documented machine schema aligned with function-only Java validation.
 */
class OperatorLibraryMachineSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void machineSchemaAllowsEitherOperatorsOrBuiltInFunctionsToMakeLibraryNonEmpty() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(schemaPath()));

        assertThat(schema.path("required"))
                .extracting(JsonNode::asText)
                .containsExactly("schemaVersion", "libraryId");
        assertThat(schema.path("anyOf")).hasSize(2);
        assertThat(schema.at("/anyOf/0/required/0").asText()).isEqualTo("operators");
        assertThat(schema.at("/anyOf/0/properties/operators/minItems").asInt()).isEqualTo(1);
        assertThat(schema.at("/anyOf/1/required/0").asText()).isEqualTo("builtInFunctions");
        assertThat(schema.at("/anyOf/1/properties/builtInFunctions/minItems").asInt()).isEqualTo(1);
        assertThat(schema.at("/properties/operators/minItems").isMissingNode()).isTrue();
    }

    @Test
    void machineSchemaAcceptsOperatorOnlyFunctionOnlyAndMixedDocuments() throws Exception {
        OperatorLibrary functionOnly = functionLibrary(List.of(function("risk.normalize")), List.of());
        OperatorLibrary operatorOnly = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary mixed = functionLibrary(
                List.of(function("risk.normalize")),
                List.of(VisualCatalogTestSupport.eligibilityOperator("integer"))
        );

        assertThat(validate(functionOnly)).isEmpty();
        assertThat(validate(operatorOnly)).isEmpty();
        assertThat(validate(mixed)).isEmpty();
    }

    @Test
    void machineSchemaRejectsMissingEmptyAndNullOnlyContributionLists() throws Exception {
        Map<String, Object> missing = Map.of(
                "schemaVersion", "bloge.visualOperatorLibrary.v1",
                "libraryId", "empty-library"
        );
        OperatorLibrary empty = functionLibrary(List.of(), List.of());
        OperatorLibrary nullOnly = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "null-only-library",
                "Null only",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                java.util.Arrays.asList((OperatorLibrary.BuiltInFunction) null),
                java.util.Arrays.asList((OperatorDefinition) null)
        );

        assertThat(validate(missing)).isNotEmpty();
        assertThat(validate(empty)).isNotEmpty();
        assertThat(validate(nullOnly)).isNotEmpty();
    }

    private List<VisualDiagnostic> validate(Object value) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = objectMapper.readValue(
                Files.readString(schemaPath()), Map.class);
        Object instance = value instanceof OperatorLibrary library
                ? objectMapper.convertValue(library, Map.class)
                : value;
        return VisualSchemaValidator.validateValue(
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema),
                instance,
                "/library"
        );
    }

    private static Path schemaPath() {
        return Path.of("..", "docs", "schemas", "bloge-visual-operator-library.schema.json");
    }

    private static OperatorLibrary functionLibrary(List<OperatorLibrary.BuiltInFunction> functions,
                                                    List<OperatorDefinition> operators) {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-functions",
                "Risk functions",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                functions,
                operators
        );
    }

    private static OperatorLibrary.BuiltInFunction function(String name) {
        return new OperatorLibrary.BuiltInFunction(
                name,
                "risk",
                name,
                "",
                "risk",
                List.of(new OperatorLibrary.Signature(
                        name + "(value)",
                        "",
                        List.of(new OperatorLibrary.Parameter("value", "any", null, false, false, "")),
                        OperatorLibrary.ReturnValue.any()
                )),
                List.of()
        );
    }
}
