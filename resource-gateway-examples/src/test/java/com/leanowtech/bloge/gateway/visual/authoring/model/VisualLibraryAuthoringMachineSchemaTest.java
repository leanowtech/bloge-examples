package com.leanowtech.bloge.gateway.visual.authoring.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class VisualLibraryAuthoringMachineSchemaTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new YAMLMapper();

    @Test
    void schemaMatchesTheJavaRootContractAndPublishedLimits() throws Exception {
        JsonNode schema = json.readTree(Files.readString(schemaPath()));

        assertThat(schema.path("required"))
                .extracting(JsonNode::asText)
                .containsExactly("schemaVersion", "library");
        assertThat(schema.path("properties").properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "schemaVersion", "library", "defaults", "types",
                        "operators", "functions", "imports", "examples");
        assertThat(schema.at("/properties/operators/maxProperties").asInt()).isEqualTo(1000);
        assertThat(schema.at("/properties/functions/maxProperties").asInt()).isEqualTo(2000);
        assertThat(schema.at("/$defs/function/properties/signatures/maxItems").asInt()).isEqualTo(20);
        assertThat(schema.at("/$defs/structuredType/properties/fields/maxProperties").asInt()).isEqualTo(2000);
    }

    @Test
    void schemaAcceptsOperatorOnlyFunctionOnlyAndMixedAuthoringDocuments() throws Exception {
        Map<String, Object> operatorOnly = source("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: customer-support, owner: support-platform}
                types:
                  Ticket:
                    fields:
                      id: string
                      priority?:
                        enum: [p0, p1, p2]
                operators:
                  support:classify:
                    archetype: pure
                    input: {ticket: Ticket}
                    output: {priority: string}
                    tests:
                      - ref: fixtures/classify-p0
                """);
        Map<String, Object> functionOnly = source("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: support-functions}
                functions:
                  support.normalize:
                    signature: "(text: string) -> string"
                    tests:
                      - ref: fixtures/normalize-spaces
                """);
        Map<String, Object> mixed = source("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: support-mixed}
                operators:
                  support:echo:
                    input: {value: any}
                    output: {value: any}
                functions:
                  support.coalesce:
                    signatures:
                      - "(value: any, fallback?: any) -> any"
                      - "(values: any[]) -> any"
                """);

        assertThat(validate(operatorOnly)).isEmpty();
        assertThat(validate(functionOnly)).isEmpty();
        assertThat(validate(mixed)).isEmpty();
    }

    @Test
    void schemaRejectsEmptyUnknownAndOutOfQuotaDocuments() throws Exception {
        Map<String, Object> empty = source("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: empty}
                """);
        Map<String, Object> unknown = source("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: unsafe}
                operators:
                  support:echo:
                    input:
                      value:
                        $ref: https://attacker.example/schema
                    output: {value: any}
                """);
        List<String> signatures = IntStream.range(0, 21)
                .mapToObj(index -> "(value" + index + ": string) -> string")
                .toList();
        Map<String, Object> tooManySignatures = Map.of(
                "schemaVersion", VisualLibraryAuthoringDocument.SCHEMA_VERSION,
                "library", Map.of("id", "too-many-signatures"),
                "functions", Map.of(
                        "normalize", Map.of("signatures", signatures)
                )
        );

        assertThat(validate(empty)).isNotEmpty();
        assertThat(validate(unknown))
                .extracting(VisualDiagnostic::target)
                .anyMatch(target -> target.contains("/input/value"));
        assertThat(validate(tooManySignatures))
                .extracting(VisualDiagnostic::target)
                .anyMatch(target -> target.contains("signatures"));
    }

    private List<VisualDiagnostic> validate(Object value) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = json.readValue(
                Files.readString(schemaPath()), Map.class);
        return VisualSchemaValidator.validateValue(
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema),
                value,
                "/authoring"
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> source(String value) throws Exception {
        return yaml.readValue(value, Map.class);
    }

    private static Path schemaPath() {
        return Path.of("..", "docs", "schemas",
                "bloge-visual-library-authoring-v1.schema.json");
    }
}
