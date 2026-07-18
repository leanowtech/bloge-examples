package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.ToolStudioResourceGatewayProtocol;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuiteStabilityServingInventoryProtocolSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void strictSchemaMatchesSerializedInventoryMaterialAndSignatures() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(schemaPath()));
        TestSuiteStabilityServingInventory inventory = inventory();
        JsonNode serialized = objectMapper.valueToTree(inventory);

        assertProperties(serialized, schema.at("/$defs/inventory/properties"));
        assertProperties(serialized.path("material"),
                schema.at("/$defs/material/properties"));
        assertProperties(serialized.path("signatures").get(0),
                schema.at("/$defs/authoritySignature/properties"));
        assertThat(schema.at("/$defs/inventory/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityServingInventory.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/material/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityServingInventory.Material.SCHEMA_VERSION);
        assertThat(ProtocolFingerprint.of(objectMapper, inventory.material()))
                .isEqualTo("sha256:aa895f2dcd3491b286f2cbd5c59ac472"
                        + "ddca87c5c837ff5a067c72c049918414");
        assertThat(List.of("inventory", "material", "authoritySignature"))
                .allSatisfy(definition -> assertThat(
                        schema.at("/$defs/" + definition + "/additionalProperties")
                                .asBoolean()).isFalse());
    }

    @Test
    void schemaCarriesOnlyPublicDeploymentFactsAndBoundedExactInventory() throws Exception {
        String schema = Files.readString(schemaPath());
        JsonNode parsed = objectMapper.readTree(schema);

        assertThat(parsed.at("/$defs/material/properties/expectedInstanceIds/minItems")
                .asInt()).isOne();
        assertThat(parsed.at("/$defs/material/properties/expectedInstanceIds/maxItems")
                .asInt()).isEqualTo(256);
        assertThat(parsed.at("/$defs/material/properties/expectedInstanceIds/uniqueItems")
                .asBoolean()).isTrue();
        assertThat(parsed.at("/$defs/inventory/properties/signatures/maxItems").asInt())
                .isEqualTo(32);
        for (String forbidden : List.of("credential", "privateKey", "payload", "fixture",
                "context", "nodeOutput", "endpoint", "secret")) {
            assertThat(schema).doesNotContain("\"" + forbidden + "\"");
        }
    }

    @Test
    void strictPublicationSchemaMatchesSignedStateAndIndependentWitness() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(publicationSchemaPath()));
        TestSuiteStabilityServingInventoryPublication publication = publication();
        JsonNode serialized = objectMapper.valueToTree(publication);

        assertProperties(serialized, schema.at("/$defs/publication/properties"));
        assertProperties(serialized.path("material"),
                schema.at("/$defs/publicationMaterial/properties"));
        assertProperties(serialized.path("witness"),
                schema.at("/$defs/witness/properties"));
        assertProperties(serialized.path("witness").path("material"),
                schema.at("/$defs/witnessMaterial/properties"));
        assertThat(schema.at("/$defs/publication/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityServingInventoryPublication.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/publicationMaterial/properties/state/enum"))
                .extracting(JsonNode::asText).containsExactly("ACTIVE", "REVOKED");
        assertThat(schema.at(
                "/$defs/publicationMaterial/allOf/0/if/properties/sequence/const")
                .asLong()).isOne();
        assertThat(schema.at(
                "/$defs/publicationMaterial/allOf/0/then/properties/previousPublicationFingerprint/const")
                .asText()).isEmpty();
        assertThat(schema.at(
                "/$defs/witnessMaterial/allOf/0/if/properties/sequence/const")
                .asLong()).isOne();
        assertThat(schema.at(
                "/$defs/witnessMaterial/allOf/0/then/properties/previousWitnessFingerprint/const")
                .asText()).isEmpty();
        assertThat(ProtocolFingerprint.of(objectMapper, publication.material()))
                .isEqualTo("sha256:b1a05ea0b8ce3108fe7446dda054563d"
                        + "c5920cf6f7ad4c3379b790ea49f32d7c");
        assertThat(ProtocolFingerprint.of(objectMapper,
                publication.witness().material()))
                .isEqualTo("sha256:0534c1e48b46b8a0d178ab1e1cf4983"
                        + "f3a6b5d09317bb01473aec6815729a2b7");
        assertThat(List.of("publication", "publicationMaterial", "witness",
                "witnessMaterial")).allSatisfy(definition -> assertThat(
                        schema.at("/$defs/" + definition + "/additionalProperties")
                                .asBoolean()).isFalse());
    }

    @Test
    void publicationSchemaExcludesCredentialsEndpointsAndPrivateMaterial() throws Exception {
        String schema = Files.readString(publicationSchemaPath());

        for (String forbidden : List.of("credential", "privateKey", "payload", "fixture",
                "context", "nodeOutput", "endpoint", "secret", "etag")) {
            assertThat(schema).doesNotContain("\"" + forbidden + "\"");
        }
    }

    private static TestSuiteStabilityServingInventory inventory() {
        Instant issuedAt = Instant.parse("2026-07-19T00:00:00Z");
        var material = new TestSuiteStabilityServingInventory.Material(
                TestSuiteStabilityServingInventory.Material.SCHEMA_VERSION,
                "deployment.example", "inventory-17", 17,
                "scope-a", "cohort-a", "sha256:" + "a".repeat(64),
                ToolStudioResourceGatewayProtocol.VERSION,
                List.of("replica-a", "replica-b"), "sha256:" + "b".repeat(64),
                issuedAt, issuedAt, issuedAt.plusSeconds(3600));
        var signature = new TestSuiteStabilityServingInventory.AuthoritySignature(
                "deployment-authority-a", "key-a", "Ed25519", issuedAt,
                Base64.getEncoder().encodeToString(new byte[64]));
        return new TestSuiteStabilityServingInventory(
                TestSuiteStabilityServingInventory.SCHEMA_VERSION,
                material, "sha256:" + "c".repeat(64), List.of(signature));
    }

    private static TestSuiteStabilityServingInventoryPublication publication() {
        Instant issuedAt = Instant.parse("2026-07-19T00:00:00Z");
        TestSuiteStabilityServingInventory inventory = inventory();
        var material = new TestSuiteStabilityServingInventoryPublication.Material(
                TestSuiteStabilityServingInventoryPublication.Material.SCHEMA_VERSION,
                "deployment.example", "publication-17", 17,
                inventory.materialFingerprint(),
                TestSuiteStabilityServingInventoryPublication.State.ACTIVE,
                "sha256:" + "b".repeat(64), "sha256:" + "d".repeat(64),
                issuedAt, issuedAt, issuedAt.plusSeconds(600), "");
        String publicationFingerprint =
                "sha256:b1a05ea0b8ce3108fe7446dda054563dc5920cf6f7ad4c3379b790ea49f32d7c";
        var witnessMaterial =
                new TestSuiteStabilityServingInventoryPublication.WitnessMaterial(
                        TestSuiteStabilityServingInventoryPublication.WitnessMaterial
                                .SCHEMA_VERSION,
                        "deployment-witness.example", "checkpoint-17", 17,
                        publicationFingerprint, "sha256:" + "f".repeat(64),
                        issuedAt, issuedAt, issuedAt.plusSeconds(600));
        var witnessSignature = new TestSuiteStabilityServingInventory.AuthoritySignature(
                "witness-authority-a", "witness-key-a", "Ed25519", issuedAt,
                Base64.getEncoder().encodeToString(new byte[64]));
        var witness = new TestSuiteStabilityServingInventoryPublication.WitnessCheckpoint(
                TestSuiteStabilityServingInventoryPublication.WitnessCheckpoint.SCHEMA_VERSION,
                witnessMaterial,
                "sha256:0534c1e48b46b8a0d178ab1e1cf4983f3a6b5d09317bb01473aec6815729a2b7",
                List.of(witnessSignature));
        var publicationSignature = new TestSuiteStabilityServingInventory.AuthoritySignature(
                "deployment-authority-a", "key-a", "Ed25519", issuedAt,
                Base64.getEncoder().encodeToString(new byte[64]));
        return new TestSuiteStabilityServingInventoryPublication(
                TestSuiteStabilityServingInventoryPublication.SCHEMA_VERSION,
                inventory, material, publicationFingerprint,
                List.of(publicationSignature), witness);
    }

    private static Path schemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "suite-stability-serving-inventory-v1.schema.json");
    }

    private static Path publicationSchemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "suite-stability-serving-inventory-publication-v1.schema.json");
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
