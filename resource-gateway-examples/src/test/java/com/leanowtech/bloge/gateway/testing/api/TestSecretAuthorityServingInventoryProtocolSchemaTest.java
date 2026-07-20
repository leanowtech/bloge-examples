package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestSecretAuthorityServingInventoryProtocolSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void strictSchemaMatchesEnvelopeMaterialAndSignatureProtocols() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(Path.of("..", "docs", "schemas",
                "resource-gateway-testing",
                "test-secret-authority-serving-inventory-v1.schema.json")));
        var material = new TestSecretAuthorityServingInventory.Material(
                TestSecretAuthorityServingInventory.Material.SCHEMA_VERSION,
                "inventory.example", "inventory-1", 1, "scope-a", "cohort-a",
                "sha256:" + "a".repeat(64), TestSecretAuthorityResponse.SCHEMA_VERSION,
                "secret-authority.example", List.of("replica-a"),
                "sha256:" + "b".repeat(64),
                Instant.parse("2026-07-20T00:00:00Z"),
                Instant.parse("2026-07-20T00:00:00Z"),
                Instant.parse("2026-07-20T01:00:00Z"));
        var signature = new TestSecretAuthorityServingInventory.AuthoritySignature(
                "inventory-authority", "key-a", "Ed25519",
                Instant.parse("2026-07-20T00:00:01Z"),
                java.util.Base64.getEncoder().encodeToString(new byte[64]));
        var inventory = new TestSecretAuthorityServingInventory(
                TestSecretAuthorityServingInventory.SCHEMA_VERSION, material,
                "sha256:" + "c".repeat(64), List.of(signature));

        assertProperties(objectMapper.valueToTree(material),
                schema.at("/$defs/material/properties"));
        assertProperties(objectMapper.valueToTree(signature),
                schema.at("/$defs/authoritySignature/properties"));
        assertProperties(objectMapper.valueToTree(inventory),
                schema.at("/$defs/inventory/properties"));
        assertThat(schema.at("/$defs/material/properties/schemaVersion/const").asText())
                .isEqualTo(TestSecretAuthorityServingInventory.Material.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/inventory/properties/schemaVersion/const").asText())
                .isEqualTo(TestSecretAuthorityServingInventory.SCHEMA_VERSION);
        assertThat(List.of("material", "authoritySignature", "inventory"))
                .allSatisfy(name -> assertThat(schema.at("/$defs/" + name
                        + "/additionalProperties").asBoolean()).isFalse());
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        assertThat(fieldNames(value)).containsExactlyInAnyOrderElementsOf(fieldNames(properties));
    }

    private static LinkedHashSet<String> fieldNames(JsonNode value) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
