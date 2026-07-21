package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation.AuthoritySignature;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation.Material;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTest.lane;
import static org.assertj.core.api.Assertions.assertThat;

class ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryProtocolSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void strictSchemaMatchesSerializedAttestationMaterialLaneAndSignature() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(schemaPath()));
        var attestation = attestation();
        JsonNode serialized = objectMapper.valueToTree(attestation);

        assertProperties(serialized, schema.at("/$defs/attestation/properties"));
        assertProperties(serialized.path("material"),
                schema.at("/$defs/material/properties"));
        assertProperties(serialized.path("material").path("laneDescriptors").get(0),
                schema.at("/$defs/laneDescriptor/properties"));
        assertProperties(serialized.path("material").path("laneDescriptors").get(0)
                        .path("expectedBinding"),
                schema.at("/$defs/expectedBinding/properties"));
        assertProperties(serialized.path("signatures").get(0),
                schema.at("/$defs/authoritySignature/properties"));
        assertThat(schema.at("/$defs/attestation/properties/schemaVersion/const").asText())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                        .SCHEMA_VERSION);
        assertThat(schema.at("/$defs/material/properties/schemaVersion/const").asText())
                .isEqualTo(Material.SCHEMA_VERSION);
        assertThat(List.of("attestation", "material", "laneDescriptor",
                "expectedBinding", "authoritySignature")).allSatisfy(definition ->
                assertThat(schema.at("/$defs/" + definition + "/additionalProperties")
                        .asBoolean()).isFalse());
    }

    @Test
    void schemaPermitsSignedEmptyDrainButBoundsInventoryAndExcludesPrivateMaterial()
            throws Exception {
        String source = Files.readString(schemaPath());
        JsonNode schema = objectMapper.readTree(source);

        assertThat(schema.at("/$defs/material/properties/laneDescriptors/minItems").asInt())
                .isZero();
        assertThat(schema.at("/$defs/material/properties/laneDescriptors/maxItems").asInt())
                .isEqualTo(256);
        assertThat(schema.at("/$defs/material/properties/laneDescriptors/uniqueItems")
                .asBoolean()).isTrue();
        assertThat(schema.at("/$defs/material/properties/partitionCount/maximum").asInt())
                .isEqualTo(64);
        assertThat(schema.at("/$defs/attestation/properties/signatures/maxItems").asInt())
                .isEqualTo(32);
        for (String forbidden : List.of("credential", "privateKey", "payload", "fixture",
                "context", "nodeOutput", "endpoint", "secret", "service",
                "authorityResolver")) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
    }

    @Test
    void schemaScalarTypesAndPatternsMatchStrictIsoWireSerialization() throws Exception {
        ObjectMapper wireMapper = objectMapper.copy()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
        JsonNode schema = wireMapper.readTree(Files.readString(schemaPath()));
        JsonNode serialized = wireMapper.valueToTree(attestation());
        JsonNode material = serialized.path("material");
        JsonNode binding = material.path("laneDescriptors").get(0)
                .path("expectedBinding");
        JsonNode signature = serialized.path("signatures").get(0);

        assertThat(material.path("generation").isIntegralNumber()).isTrue();
        assertThat(material.path("partitionCount").isIntegralNumber()).isTrue();
        assertThat(material.path("laneDescriptors").isArray()).isTrue();
        for (String field : List.of("issuedAt", "notBefore", "expiresAt")) {
            assertThat(material.path(field).isTextual()).isTrue();
            assertThat(schema.at("/$defs/material/properties/" + field + "/type").asText())
                    .isEqualTo("string");
            assertThat(schema.at("/$defs/material/properties/" + field + "/format").asText())
                    .isEqualTo("date-time");
        }
        for (String field : List.of("maximumRootLifetime", "clockSkew",
                "minimumRemainingValidity")) {
            assertThat(binding.path(field).isTextual()).isTrue();
            assertThat(schema.at("/$defs/expectedBinding/properties/" + field
                    + "/type").asText()).isEqualTo("string");
            assertThat(schema.at("/$defs/expectedBinding/properties/" + field
                    + "/format").asText()).isEqualTo("duration");
        }
        assertThat(serialized.path("materialFingerprint").asText())
                .matches(schema.at("/$defs/fingerprint/pattern").asText());
        assertThat(signature.path("signature").asText())
                .matches(schema.at("/$defs/authoritySignature/properties/signature/pattern")
                        .asText());
        assertThat(signature.path("algorithm").asText())
                .isEqualTo(schema.at(
                        "/$defs/authoritySignature/properties/algorithm/const").asText());
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
            attestation() {
        Instant issuedAt = Instant.parse("2026-07-21T00:00:00Z");
        var material = new Material(Material.SCHEMA_VERSION, "fleet-inventory.example",
                "inventory-17", 17L, "recovery-prod", "bootstrap-recovery",
                "sha256:" + "a".repeat(64), 4,
                List.of(lane("tenant", "roots-a", 'a').descriptor()),
                "sha256:" + "b".repeat(64), issuedAt, issuedAt,
                issuedAt.plusSeconds(3_600));
        var signature = new AuthoritySignature("inventory-authority-a", "key-a",
                "Ed25519", issuedAt,
                Base64.getEncoder().encodeToString(new byte[64]));
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                        .SCHEMA_VERSION,
                material, "sha256:" + "c".repeat(64), List.of(signature));
    }

    private static Path schemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "external-sequence-anchor-bootstrap-root-recovery-fleet-inventory-v1.schema.json");
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        assertThat(value.properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrderElementsOf(
                        properties.properties().stream().map(java.util.Map.Entry::getKey).toList());
    }
}
