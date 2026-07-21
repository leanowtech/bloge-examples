package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneCertificateStatusSourceHeadProtocolSchemaTest {

    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void strictSchemaExactlyMatchesEverySerializedRecord() throws Exception {
        JsonNode schema = schema();
        ControlPlaneCertificateStatusSourceHead sourceHead = sourceHead();

        assertProperties(objectMapper.valueToTree(sourceHead),
                schema.at("/$defs/sourceHead/properties"));
        assertProperties(objectMapper.valueToTree(sourceHead.material()),
                schema.at("/$defs/material/properties"));
        assertProperties(objectMapper.valueToTree(sourceHead.signatures().getFirst()),
                schema.at("/$defs/authoritySignature/properties"));
        for (String definition : List.of("sourceHead", "material", "authoritySignature")) {
            assertThat(schema.at("/$defs/" + definition + "/additionalProperties")
                    .asBoolean(true)).isFalse();
        }
    }

    @Test
    void schemaVersionsBoundsAndSensitiveFieldExclusionsAreFrozen() throws Exception {
        JsonNode schema = schema();

        assertThat(schema.at("/$defs/sourceHead/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateStatusSourceHead.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/material/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateStatusSourceHead.Material.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/sourceHead/properties/signatures/maxItems").asInt())
                .isEqualTo(32);
        String source = Files.readString(schemaPath());
        for (String forbidden : new String[]{
                "certificateBytes", "certificatePem", "privateKey", "password",
                "secretRef", "keyStore", "trustStore", "responderUrl", "crlUrl",
                "ocspResponse", "crlPayload", "endpointUri", "exception", "stackTrace"}) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
    }

    private static ControlPlaneCertificateStatusSourceHead sourceHead() {
        Instant now = Instant.parse("2026-07-22T00:00:00Z");
        var material = new ControlPlaneCertificateStatusSourceHead.Material(
                ControlPlaneCertificateStatusSourceHead.Material.SCHEMA_VERSION,
                "enterprise-pki", "head-001", "rg-staging", 42,
                FINGERPRINT, FINGERPRINT, now, now.plusSeconds(60));
        var signature = new ControlPlaneCertificateStatusPublication.AuthoritySignature(
                "authority-a", "key-a", "Ed25519", now,
                Base64.getEncoder().encodeToString(new byte[64]));
        return new ControlPlaneCertificateStatusSourceHead(
                ControlPlaneCertificateStatusSourceHead.SCHEMA_VERSION,
                material, FINGERPRINT, List.of(signature));
    }

    private static JsonNode schema() throws Exception {
        return new ObjectMapper().readTree(Files.readString(schemaPath()));
    }

    private static Path schemaPath() {
        Path moduleRelative = Path.of("..", "docs", "schemas",
                "resource-gateway-testing",
                "control-plane-certificate-status-source-head-v1.schema.json");
        return Files.exists(moduleRelative) ? moduleRelative : Path.of("docs", "schemas",
                "resource-gateway-testing",
                "control-plane-certificate-status-source-head-v1.schema.json");
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        assertThat(value.properties().stream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrderElementsOf(
                        properties.properties().stream().map(Map.Entry::getKey).toList());
    }
}
