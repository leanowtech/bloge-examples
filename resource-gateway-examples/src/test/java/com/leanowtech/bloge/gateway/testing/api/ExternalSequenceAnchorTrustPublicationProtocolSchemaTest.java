package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSequenceAnchorTrustPublicationProtocolSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void strictSchemaMatchesCanonicalManagedTrustPublication() throws Exception {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        String publicKey = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                        .getPublic().getEncoded());
        var key = new ExternalSequenceAnchorTrustPublication.AuthorityKeyMaterial(
                "notary-a", "notary-key-a", publicKey, now.minusSeconds(60),
                now.plusSeconds(3600), true, false);
        var material = new ExternalSequenceAnchorTrustPublication.Material(
                ExternalSequenceAnchorTrustPublication.Material.SCHEMA_VERSION,
                "notary-trust-roots", 1, "", "stability-fleet", "notary-set-a",
                "notary.example", "notary-bootstrap.example", 1, 0, List.of(key),
                "sha256:" + "a".repeat(64), now, now, now.plusSeconds(3600));
        var signature = new TestSuiteStabilityServingInventory.AuthoritySignature(
                "bootstrap-a", "bootstrap-key-a", "Ed25519", now,
                Base64.getEncoder().encodeToString(new byte[64]));
        var publication = new ExternalSequenceAnchorTrustPublication(
                ExternalSequenceAnchorTrustPublication.SCHEMA_VERSION, material,
                ProtocolFingerprint.of(objectMapper, material), List.of(signature));
        JsonNode schema = objectMapper.readTree(Files.readString(schemaPath()));

        assertProperties(objectMapper.valueToTree(publication),
                schema.at("/$defs/publication/properties"));
        assertProperties(objectMapper.valueToTree(material),
                schema.at("/$defs/material/properties"));
        assertProperties(objectMapper.valueToTree(key),
                schema.at("/$defs/authorityKeyMaterial/properties"));
        assertThat(schema.at("/$defs/publication/properties/schemaVersion/const").asText())
                .isEqualTo(ExternalSequenceAnchorTrustPublication.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/material/properties/schemaVersion/const").asText())
                .isEqualTo(ExternalSequenceAnchorTrustPublication.Material.SCHEMA_VERSION);
        assertThat(List.of("publication", "material", "authorityKeyMaterial"))
                .allSatisfy(definition -> assertThat(schema.at(
                        "/$defs/" + definition + "/additionalProperties").asBoolean())
                        .isFalse());
    }

    @Test
    void schemaContainsOnlyPublicTrustMaterial() throws Exception {
        String schema = Files.readString(schemaPath());

        for (String forbidden : List.of("privateKey", "credential", "payload", "fixture",
                "nodeOutput", "endpoint", "etag", "requestFingerprint")) {
            assertThat(schema).doesNotContain("\"" + forbidden + "\"");
        }
    }

    private static Path schemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "external-sequence-anchor-trust-publication-v1.schema.json");
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        assertThat(fieldNames(value)).containsExactlyInAnyOrderElementsOf(
                fieldNames(properties));
    }

    private static LinkedHashSet<String> fieldNames(JsonNode value) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
