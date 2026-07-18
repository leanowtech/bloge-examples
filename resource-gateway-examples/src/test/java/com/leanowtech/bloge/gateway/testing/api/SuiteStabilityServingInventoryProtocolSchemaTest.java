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

    private static Path schemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "suite-stability-serving-inventory-v1.schema.json");
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
