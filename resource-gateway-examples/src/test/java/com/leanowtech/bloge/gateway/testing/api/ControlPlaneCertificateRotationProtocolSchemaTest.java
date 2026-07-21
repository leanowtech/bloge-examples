package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneCertificateRotationProtocolSchemaTest {

    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void strictSchemasExactlyMatchSerializedEventAndResultFields() throws Exception {
        JsonNode eventSchema = schema("control-plane-certificate-rotation-event-v1.schema.json");
        JsonNode resultSchema = schema(
                "control-plane-certificate-rotation-apply-result-v1.schema.json");
        ControlPlaneCertificateRotationEvent event = event();
        var result = new ControlPlaneCertificateRotationController.ApplyResult(
                ControlPlaneCertificateRotationController.ApplyResult.SCHEMA_VERSION,
                ControlPlaneCertificateRotationController.ApplyStatus.APPLIED,
                "APPLIED", "rotation-002", FINGERPRINT, 1, 2);

        assertProperties(objectMapper.valueToTree(event),
                eventSchema.at("/$defs/event/properties"));
        assertProperties(objectMapper.valueToTree(event.material()),
                eventSchema.at("/$defs/material/properties"));
        assertProperties(objectMapper.valueToTree(event.signatures().getFirst()),
                eventSchema.at("/$defs/authoritySignature/properties"));
        assertProperties(objectMapper.valueToTree(result), resultSchema.path("properties"));
        assertThat(eventSchema.at("/$defs/event/additionalProperties").asBoolean(true)).isFalse();
        assertThat(eventSchema.at("/$defs/material/additionalProperties").asBoolean(true))
                .isFalse();
        assertThat(eventSchema.at(
                "/$defs/authoritySignature/additionalProperties").asBoolean(true)).isFalse();
        assertThat(resultSchema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void schemaVocabulariesAndProtocolVersionsMatchJavaExactly() throws Exception {
        JsonNode eventSchema = schema("control-plane-certificate-rotation-event-v1.schema.json");
        JsonNode resultSchema = schema(
                "control-plane-certificate-rotation-apply-result-v1.schema.json");

        assertThat(eventSchema.at("/$defs/event/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateRotationEvent.SCHEMA_VERSION);
        assertThat(eventSchema.at("/$defs/material/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateRotationEvent.Material.SCHEMA_VERSION);
        assertThat(resultSchema.at("/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateRotationController.ApplyResult.SCHEMA_VERSION);
        assertThat(resultSchema.at("/properties/status/enum"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(Arrays.stream(
                                ControlPlaneCertificateRotationController.ApplyStatus.values())
                        .map(Enum::name).toArray(String[]::new));
        assertThat(eventSchema.at("/$defs/event/properties/signatures/maxItems").asInt())
                .isEqualTo(32);
        assertThat(eventSchema.at("/$defs/material/properties/generation/minimum").asLong())
                .isEqualTo(2);
        assertThat(eventSchema.at("/$defs/materialId/pattern").asText())
                .doesNotContain(":", "/", "#");
    }

    @Test
    void applyResultSchemaCannotCarryTlsMaterialOrResolverFailureDetails() throws Exception {
        String source = Files.readString(schemaPath(
                "control-plane-certificate-rotation-apply-result-v1.schema.json"));

        for (String forbidden : new String[]{
                "materialId", "settingsFingerprint", "policyFingerprint", "certificate",
                "privateKey", "password", "secretRef", "keyStore", "trustStore", "path",
                "exception", "errorMessage", "stackTrace"}) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
    }

    private ControlPlaneCertificateRotationEvent event() {
        Instant now = Instant.parse("2026-07-21T12:00:00Z");
        var material = new ControlPlaneCertificateRotationEvent.Material(
                ControlPlaneCertificateRotationEvent.Material.SCHEMA_VERSION,
                "enterprise-pki", "rotation-002", "rg-staging-sg",
                "recovery-fleet.publisher", 2, FINGERPRINT, "candidate-b",
                FINGERPRINT, FINGERPRINT, now.minusSeconds(30), now.minusSeconds(20),
                now.plusSeconds(300), now.plusSeconds(3_600));
        var signature = new ControlPlaneCertificateRotationEvent.AuthoritySignature(
                "authority-a", "key-a", "Ed25519", now,
                Base64.getEncoder().encodeToString(new byte[64]));
        return new ControlPlaneCertificateRotationEvent(
                ControlPlaneCertificateRotationEvent.SCHEMA_VERSION,
                material, FINGERPRINT, List.of(signature));
    }

    private JsonNode schema(String file) throws Exception {
        return objectMapper.readTree(Files.readString(schemaPath(file)));
    }

    private static Path schemaPath(String file) {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing", file);
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        properties.fieldNames().forEachRemaining(expected::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }
}
