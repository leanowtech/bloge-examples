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

class SuiteStabilityExternalSequenceCheckpointProtocolSchemaTest {

    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void strictSchemaMatchesSerializedHeadRequestAndSignedReceipt() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(schemaPath()));
        var head = new TestSuiteStabilityExternalSequenceAnchor.Head(
                TestSuiteStabilityExternalSequenceAnchor.Head.SCHEMA_VERSION,
                TestSuiteStabilityExternalSequenceAnchor.StreamKind
                        .SERVING_INVENTORY_PUBLICATION,
                "stability-fleet", "serving-inventory-publication", 1, SHA_A, "");
        String challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[32]);
        var request = TestSuiteStabilityExternalSequenceCheckpointRequest.create(
                objectMapper, "inventory-transparency", "notary-set-a", head,
                challenge, NOW, NOW.plusSeconds(10));
        var material = new TestSuiteStabilityExternalSequenceCheckpointReceipt.Material(
                TestSuiteStabilityExternalSequenceCheckpointReceipt.SCHEMA_VERSION,
                request.requestFingerprint(), request.trustDomain(), request.anchorSetId(),
                "notary-a", "region-a", "key-a",
                TestSuiteStabilityExternalSequenceCheckpointReceipt.Decision.ACCEPTED,
                1, SHA_A, 1, SHA_A, NOW, NOW.plusSeconds(10), "Ed25519");
        var receipt = new TestSuiteStabilityExternalSequenceCheckpointReceipt(
                TestSuiteStabilityExternalSequenceCheckpointReceipt.SCHEMA_VERSION,
                ProtocolFingerprint.of(objectMapper, material), request.requestFingerprint(),
                request.trustDomain(), request.anchorSetId(), "notary-a", "region-a", "key-a",
                TestSuiteStabilityExternalSequenceCheckpointReceipt.Decision.ACCEPTED,
                1, SHA_A, 1, SHA_A, NOW, NOW.plusSeconds(10), "Ed25519",
                Base64.getEncoder().encodeToString(new byte[64]));

        assertProperties(objectMapper.valueToTree(head), schema.at("/$defs/head/properties"));
        assertProperties(objectMapper.valueToTree(request),
                schema.at("/$defs/request/properties"));
        assertProperties(objectMapper.valueToTree(receipt),
                schema.at("/$defs/receipt/properties"));
        assertThat(schema.at("/$defs/head/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityExternalSequenceAnchor.Head.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/request/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityExternalSequenceCheckpointRequest.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/receipt/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityExternalSequenceCheckpointReceipt.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/request/properties/challenge/minLength").asInt())
                .isEqualTo(43);
        assertThat(schema.at("/$defs/receipt/properties/signature/minLength").asInt())
                .isEqualTo(88);
        assertThat(List.of("head", "request", "receipt"))
                .allSatisfy(definition -> assertThat(schema.at(
                        "/$defs/" + definition + "/additionalProperties").asBoolean())
                        .isFalse());
    }

    @Test
    void schemaExcludesEndpointsKeysPayloadsAndPrivateChainMaterial() throws Exception {
        String schema = Files.readString(schemaPath());

        for (String forbidden : List.of("privateKey", "credential", "payload", "fixture",
                "context", "nodeOutput", "endpoint", "uri", "etag")) {
            assertThat(schema).doesNotContain("\"" + forbidden + "\"");
        }
    }

    private static Path schemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "suite-stability-external-sequence-checkpoint-v1.schema.json");
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
