package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneCertificateRotationEventCursorProtocolSchemaTest {

    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void strictSchemasExactlyMatchTheJavaPageAndCursorFields() throws Exception {
        JsonNode pageSchema = schema(
                "control-plane-certificate-rotation-event-page-v1.schema.json");
        JsonNode cursorSchema = schema(
                "control-plane-certificate-rotation-event-cursor-snapshot-v1.schema.json");
        var page = page();
        var cursor = new ControlPlaneCertificateRotationEventCursor.Snapshot(
                ControlPlaneCertificateRotationEventCursor.Snapshot.SCHEMA_VERSION,
                "resource-gateway-prod", "replica-a", 0, FINGERPRINT,
                0, FINGERPRINT, 1, FINGERPRINT, page.pageFingerprint());

        assertProperties(objectMapper.valueToTree(page), pageSchema.path("properties"));
        assertProperties(objectMapper.valueToTree(page.material()),
                pageSchema.at("/$defs/material/properties"));
        assertProperties(objectMapper.valueToTree(cursor), cursorSchema.path("properties"));
        assertThat(pageSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(pageSchema.at("/$defs/material/additionalProperties")
                .asBoolean(true)).isFalse();
        assertThat(cursorSchema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void schemaVersionsAndBoundedPageCapacityMatchJavaExactly() throws Exception {
        JsonNode pageSchema = schema(
                "control-plane-certificate-rotation-event-page-v1.schema.json");
        JsonNode cursorSchema = schema(
                "control-plane-certificate-rotation-event-cursor-snapshot-v1.schema.json");

        assertThat(pageSchema.at("/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateRotationEventPage.SCHEMA_VERSION);
        assertThat(pageSchema.at("/$defs/material/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateRotationEventPage.Material.SCHEMA_VERSION);
        assertThat(pageSchema.at("/$defs/material/properties/events/maxItems").asInt())
                .isEqualTo(ControlPlaneCertificateRotationEventPage.Material.MAXIMUM_EVENTS);
        assertThat(ControlPlaneCertificateRotationEventPage.Material.MAXIMUM_EVENTS)
                .isEqualTo(ControlPlaneCertificateRotationTargets.values().size());
        assertThat(cursorSchema.at("/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateRotationEventCursor.Snapshot.SCHEMA_VERSION);
    }

    @Test
    void cursorSchemaCannotCarryEventsMaterialCredentialsOrFailureText() throws Exception {
        String source = Files.readString(schemaPath(
                "control-plane-certificate-rotation-event-cursor-snapshot-v1.schema.json"));

        for (String forbidden : new String[]{
                "events", "materialId", "settingsFingerprint", "certificate", "privateKey",
                "password", "secretRef", "keyStore", "trustStore", "path", "exception",
                "errorMessage", "stackTrace"}) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
    }

    private ControlPlaneCertificateRotationEventPage page() {
        Instant now = Instant.parse("2026-07-21T12:00:00Z");
        var eventMaterial = new ControlPlaneCertificateRotationEvent.Material(
                ControlPlaneCertificateRotationEvent.Material.SCHEMA_VERSION,
                "certificate-authority", "rotation-002", "resource-gateway-prod",
                "target-a", 2, FINGERPRINT, "candidate-b", FINGERPRINT, FINGERPRINT,
                now, now, now.plusSeconds(10), now.plusSeconds(120));
        var event = new ControlPlaneCertificateRotationEvent(
                ControlPlaneCertificateRotationEvent.SCHEMA_VERSION, eventMaterial,
                ProtocolFingerprint.of(objectMapper, eventMaterial),
                List.of(new ControlPlaneCertificateRotationEvent.AuthoritySignature(
                        "authority-a", "key-a", "Ed25519", now,
                        Base64.getEncoder().encodeToString(new byte[64]))));
        var pageMaterial = new ControlPlaneCertificateRotationEventPage.Material(
                ControlPlaneCertificateRotationEventPage.Material.SCHEMA_VERSION,
                "resource-gateway-prod", 1, FINGERPRINT, now, now.plusSeconds(60),
                List.of(event));
        return new ControlPlaneCertificateRotationEventPage(
                ControlPlaneCertificateRotationEventPage.SCHEMA_VERSION,
                pageMaterial, ProtocolFingerprint.of(objectMapper, pageMaterial));
    }

    private JsonNode schema(String name) throws Exception {
        return objectMapper.readTree(Files.readString(schemaPath(name)));
    }

    private Path schemaPath(String name) {
        Path path = Path.of("..", "docs", "schemas", "resource-gateway-testing", name);
        return Files.exists(path) ? path : Path.of("docs", "schemas",
                "resource-gateway-testing", name);
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        assertThat(propertyNames(value)).containsExactlyInAnyOrderElementsOf(
                propertyNames(properties));
    }

    private static LinkedHashSet<String> propertyNames(JsonNode node) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
