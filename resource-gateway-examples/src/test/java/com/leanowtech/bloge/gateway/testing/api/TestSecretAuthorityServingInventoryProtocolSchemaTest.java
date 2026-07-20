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

    @Test
    void publicationSchemaMatchesDeploymentDecisionAndIndependentWitness() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(Path.of("..", "docs", "schemas",
                "resource-gateway-testing",
                "test-secret-authority-serving-inventory-publication-v1.schema.json")));
        var inventoryMaterial = new TestSecretAuthorityServingInventory.Material(
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
                TestSecretAuthorityServingInventory.SCHEMA_VERSION, inventoryMaterial,
                "sha256:" + "c".repeat(64), List.of(signature));
        var material = new TestSecretAuthorityServingInventoryPublication.Material(
                TestSecretAuthorityServingInventoryPublication.Material.SCHEMA_VERSION,
                "inventory.example", "publication-1", 1,
                inventory.materialFingerprint(),
                TestSecretAuthorityServingInventoryPublication.State.ACTIVE,
                "sha256:" + "b".repeat(64), "",
                Instant.parse("2026-07-20T00:00:00Z"),
                Instant.parse("2026-07-20T00:00:00Z"),
                Instant.parse("2026-07-20T00:10:00Z"), "");
        var witnessMaterial =
                new TestSecretAuthorityServingInventoryPublication.WitnessMaterial(
                        TestSecretAuthorityServingInventoryPublication.WitnessMaterial
                                .SCHEMA_VERSION,
                        "witness.example", "checkpoint-1", 1,
                        "sha256:" + "d".repeat(64), "",
                        Instant.parse("2026-07-20T00:00:01Z"),
                        Instant.parse("2026-07-20T00:00:01Z"),
                        Instant.parse("2026-07-20T00:10:00Z"));
        var witness = new TestSecretAuthorityServingInventoryPublication.WitnessCheckpoint(
                TestSecretAuthorityServingInventoryPublication.WitnessCheckpoint.SCHEMA_VERSION,
                witnessMaterial, "sha256:" + "e".repeat(64), List.of(signature));
        var publication = new TestSecretAuthorityServingInventoryPublication(
                TestSecretAuthorityServingInventoryPublication.SCHEMA_VERSION,
                inventory, material, "sha256:" + "d".repeat(64),
                List.of(signature), witness);

        assertProperties(objectMapper.valueToTree(material),
                schema.at("/$defs/publicationMaterial/properties"));
        assertProperties(objectMapper.valueToTree(witnessMaterial),
                schema.at("/$defs/witnessMaterial/properties"));
        assertProperties(objectMapper.valueToTree(witness),
                schema.at("/$defs/witness/properties"));
        assertProperties(objectMapper.valueToTree(publication),
                schema.at("/$defs/publication/properties"));
        assertThat(schema.at("/$defs/publication/properties/schemaVersion/const").asText())
                .isEqualTo(TestSecretAuthorityServingInventoryPublication.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/publicationMaterial/allOf")).hasSize(3);
        assertThat(schema.at("/$defs/witnessMaterial/allOf")).hasSize(1);
        assertThat(List.of("publicationMaterial", "witnessMaterial", "witness", "publication"))
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
