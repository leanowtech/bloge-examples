package com.leanowtech.bloge.gateway.testing.authoring.fixture;

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

class AuthoringFixtureMachineSchemaTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void publishedSchemasMatchWireVersionsAndPayloadBoundaries()
            throws Exception {
        JsonNode save = schema("save-request");
        JsonNode receipt = schema("receipt");
        JsonNode material = schema("material");

        assertThat(save.at("/properties/schemaVersion/const").asText())
                .isEqualTo(
                        AuthoringFixtureProtocol.SaveRequest.SCHEMA_VERSION);
        assertThat(receipt.at("/properties/schemaVersion/const").asText())
                .isEqualTo(
                        AuthoringFixtureProtocol.FixtureReceipt.SCHEMA_VERSION);
        assertThat(material.at("/properties/schemaVersion/const").asText())
                .isEqualTo(
                        AuthoringFixtureProtocol.FixtureMaterial.SCHEMA_VERSION);
        assertThat(receipt.path("properties").has("payload")).isFalse();
        assertThat(receipt.at("/properties/payloadReturned/const").asBoolean())
                .isFalse();
        assertThat(material.path("properties").has("payload")).isTrue();
        assertThat(material.at("/properties/payloadReturned/const").asBoolean())
                .isTrue();
    }

    @Test
    void saveSchemaAcceptsGovernedCommandAndRejectsUnsafeRetention()
            throws Exception {
        Map<String, Object> valid = Map.ofEntries(
                Map.entry(
                        "schemaVersion",
                        AuthoringFixtureProtocol.SaveRequest.SCHEMA_VERSION),
                Map.entry("fixtureId", "echo-golden"),
                Map.entry("expectedFixtureRevision", 0),
                Map.entry("sourceKind", "OPERATOR_TEST_CASE"),
                Map.entry("assetKind", "OPERATOR"),
                Map.entry("assetRef", "demo:echo"),
                Map.entry("classification", "CONFIDENTIAL"),
                Map.entry("retentionDays", 7),
                Map.entry(
                        "redactionPaths",
                        List.of("/inputs/customer/email")),
                Map.entry(
                        "payload",
                        Map.of("inputs", Map.of("customerId", "demo"))));
        Map<String, Object> invalid = new java.util.LinkedHashMap<>(valid);
        invalid.put("retentionDays", 31);

        assertThat(validate(valid)).isEmpty();
        assertThat(validate(invalid))
                .extracting(VisualDiagnostic::target)
                .anyMatch(target -> target.contains("retentionDays"));
    }

    private List<VisualDiagnostic> validate(Object value) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = json.readValue(
                Files.readString(schemaPath("save-request")), Map.class);
        return VisualSchemaValidator.validateValue(
                new SchemaEnvelope(
                        SchemaEnvelope.JSON_SCHEMA, "2020-12", schema),
                value,
                "/fixture");
    }

    private JsonNode schema(String kind) throws Exception {
        return json.readTree(Files.readString(schemaPath(kind)));
    }

    private static Path schemaPath(String kind) {
        return Path.of(
                "..",
                "docs",
                "schemas",
                "bloge-visual-authoring-fixture-" + kind
                        + "-v1.schema.json");
    }
}
