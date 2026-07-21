package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSequenceAnchorBootstrapRootRecoveryFleetCapabilityProtocolSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void strictSchemaExactlyMatchesReadyAndDisabledWireFields() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(schemaPath()));
        JsonNode properties = schema.path("properties");

        assertProperties(objectMapper.valueToTree(ready()), properties);
        assertProperties(objectMapper.valueToTree(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.disabled()),
                properties);
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("required")).hasSize(properties.size());
        assertThat(schema.at("/properties/schemaVersion/const").asText())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability
                        .SCHEMA_VERSION);
    }

    @Test
    void schemaStatusVocabularyAndDynamicSourceMatchJavaProtocol() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(schemaPath()));

        assertThat(schema.at("/properties/status/enum"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(Arrays.stream(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.Status
                                .values()).map(Enum::name).toArray(String[]::new));
        assertThat(schema.at("/allOf/2/then/properties/sourceType/const").asText())
                .isEqualTo(
                        DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                                .SOURCE_TYPE);
        assertThat(schema.at("/properties/laneCount/maximum").asInt())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory
                        .MAXIMUM_LANES);
    }

    @Test
    void schemaContainsReadinessAndAnchorImplicationsButNoSensitiveVocabulary()
            throws Exception {
        String source = Files.readString(schemaPath());
        JsonNode schema = objectMapper.readTree(source);

        assertThat(schema.path("allOf")).hasSize(20);
        assertThat(schema.at("/allOf/1/then/properties/status/const").asText())
                .isEqualTo("READY");
        assertThat(schema.at(
                "/allOf/3/then/properties/durablePublicationFloor/const").asBoolean())
                .isTrue();
        assertThat(schema.at(
                "/allOf/4/then/properties/externallyAnchoredPublicationFloor/const")
                .asBoolean()).isTrue();
        assertThat(schema.at(
                "/allOf/5/then/properties/dynamicInventory/const").asBoolean())
                .isTrue();
        assertThat(schema.at(
                "/allOf/6/then/properties/configured/const").asBoolean())
                .isFalse();
        assertThat(schema.at(
                "/allOf/7/then/properties/ready/const").asBoolean())
                .isTrue();
        assertThat(schema.at(
                "/allOf/8/then/properties/inventoryGeneration/minimum").asInt())
                .isOne();
        assertThat(schema.at(
                "/allOf/9/then/properties/externallyAttested/const").asBoolean())
                .isTrue();
        assertThat(schema.at(
                "/allOf/10/then/properties/dynamicInventory/const").asBoolean())
                .isTrue();
        for (String forbidden : new String[]{"deploymentScopeId", "fleetId", "laneKey",
                "endpoint", "policyFingerprint", "materialFingerprint", "privateKey",
                "credential", "payload", "exception"}) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
    }

    @Test
    void legacyV1SchemaRemainsFrozenWithoutManagedRootFields() throws Exception {
        JsonNode legacy = objectMapper.readTree(Files.readString(legacySchemaPath()));

        assertThat(legacy.at("/properties/schemaVersion/const").asText())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability
                        .SCHEMA_VERSION_V1);
        assertThat(legacy.path("properties").has("managedTrustRootRefresh")).isFalse();
        assertThat(legacy.path("properties").has("managedTrustRootStatus")).isFalse();
        assertThat(legacy.path("additionalProperties").asBoolean()).isFalse();
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability ready() {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.SCHEMA_VERSION,
                true, true,
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.Status.READY,
                true, true,
                DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                        .SOURCE_TYPE,
                17L, 2, true, true, true, true, true, true, true,
                false, false, 4L, 0L, 4L, 0L);
    }

    private static Path schemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "external-sequence-anchor-bootstrap-root-recovery-fleet-capability-v2.schema.json");
    }

    private static Path legacySchemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "external-sequence-anchor-bootstrap-root-recovery-fleet-capability-v1.schema.json");
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        assertThat(value.properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrderElementsOf(
                        properties.properties().stream().map(java.util.Map.Entry::getKey).toList());
    }
}
