package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootProtocolSchemaTest {

    private static final Path SCHEMA = Path.of("..", "docs", "schemas",
            "resource-gateway-testing",
            "external-sequence-anchor-bootstrap-root-recovery-fleet-inventory-trust-root-publication-v1.schema.json");
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void schemaMatchesEverySerializedPublicationRecordComponent() throws Exception {
        var deploymentKey = key("deployment-a", "deployment-key-a", (byte) 1);
        var witnessKey = key("witness-a", "witness-key-a", (byte) 2);
        var material = new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                .Material(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                        .Material.SCHEMA_VERSION,
                "recovery-fleet-roots", 1, "", "tenant-a/prod", "recovery-fleet-a",
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                        .SCHEMA_VERSION,
                "recovery-fleet.deployment-root", "recovery-fleet.witness-root",
                "recovery-fleet.deployment", "recovery-fleet.witness",
                1, 1, List.of(deploymentKey), List.of(witnessKey),
                "sha256:" + "a".repeat(64), NOW.minusSeconds(30), NOW.minusSeconds(30),
                NOW.plusSeconds(3600));
        var signature = new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                .AuthoritySignature(
                "root-a", "root-key-a", "Ed25519", NOW.minusSeconds(10),
                Base64.getEncoder().encodeToString(new byte[64]));
        var publication = new
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                        .SCHEMA_VERSION,
                material, "sha256:" + "b".repeat(64), List.of(signature),
                List.of(signature));
        JsonNode schema = objectMapper.readTree(Files.readString(SCHEMA));

        assertStrictProperties(objectMapper.valueToTree(publication),
                schema.at("/$defs/publication"));
        assertStrictProperties(objectMapper.valueToTree(material),
                schema.at("/$defs/material"));
        assertStrictProperties(objectMapper.valueToTree(deploymentKey),
                schema.at("/$defs/authorityKeyMaterial"));
        assertThat(schema.at("/$defs/publication/properties/schemaVersion/const").asText())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                        .SCHEMA_VERSION);
        assertThat(schema.at("/$defs/material/properties/schemaVersion/const").asText())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                        .Material.SCHEMA_VERSION);
    }

    @Test
    void schemaPinsSignatureProtocolVersionAndMonotonicPredecessorShape() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(SCHEMA));

        assertThat(schema.path("$schema").asText())
                .isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(schema.path("$ref").asText()).isEqualTo("#/$defs/publication");
        assertThat(schema.at("/$defs/authoritySignature/$ref").asText()).isEqualTo(
                "external-sequence-anchor-bootstrap-root-recovery-fleet-inventory-v1.schema.json#/$defs/authoritySignature");
        assertThat(schema.at("/$defs/material/properties/protocolVersion/const").asText())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                        .SCHEMA_VERSION);
        assertThat(schema.at("/$defs/material/allOf/0/if/properties/sequence/const").asLong())
                .isOne();
        assertThat(schema.at(
                "/$defs/material/allOf/0/then/properties/previousMaterialFingerprint/const")
                .asText()).isEmpty();
        assertThat(schema.at(
                "/$defs/material/allOf/0/else/properties/previousMaterialFingerprint/$ref")
                .asText()).isEqualTo("#/$defs/fingerprint");
        assertThat(List.of("publication", "material", "authorityKeyMaterial"))
                .allSatisfy(definition -> assertThat(schema.at(
                        "/$defs/" + definition + "/additionalProperties").asBoolean())
                        .isFalse());
    }

    @Test
    void schemaCannotCarryCustodyTransportOrBusinessData() throws Exception {
        String schema = Files.readString(SCHEMA);

        for (String forbidden : List.of("privateKey", "credential", "payload", "endpoint",
                "etag", "fixture", "context", "secretValue", "laneKey", "exception")) {
            assertThat(schema).doesNotContain("\"" + forbidden + "\"");
        }
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
            .AuthorityKeyMaterial key(String authorityId, String keyId, byte marker) {
        byte[] encoded = new byte[44];
        encoded[43] = marker;
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                .AuthorityKeyMaterial(
                authorityId, keyId, Base64.getEncoder().encodeToString(encoded),
                NOW.minusSeconds(60), NOW.plusSeconds(3600), true, false);
    }

    private static void assertStrictProperties(JsonNode value, JsonNode definition) {
        Set<String> fields = value.properties().stream()
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(definition.path("type").asText()).isEqualTo("object");
        assertThat(definition.path("additionalProperties").asBoolean()).isFalse();
        assertThat(definition.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrderElementsOf(fields);
        assertThat(definition.path("properties").properties().stream()
                .map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrderElementsOf(fields);
    }
}
