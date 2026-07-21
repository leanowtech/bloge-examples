package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.Status;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.Assessment;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.Policy;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.State;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.Violation;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSequenceAnchorBootstrapRootRecoveryFleetSloAssessmentProtocolSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void strictSchemaExactlyMatchesKnownAndUnavailableAssessmentFields() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(schemaPath()));
        JsonNode properties = schema.path("properties");

        assertProperties(objectMapper.valueToTree(healthy()), properties);
        assertProperties(objectMapper.valueToTree(unavailable()), properties);
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("required")).hasSize(properties.size());
        assertThat(schema.at("/properties/schemaVersion/const").asText())
                .isEqualTo(Assessment.SCHEMA_VERSION);
        assertThat(schema.at("/properties/policy/additionalProperties").asBoolean())
                .isFalse();
        assertThat(schema.at("/properties/policy/required")).hasSize(
                schema.at("/properties/policy/properties").size());
    }

    @Test
    void schemaVocabulariesExactlyMatchJavaEnumsAndBounds() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(schemaPath()));

        assertEnum(schema.at("/properties/state/enum"), State.values());
        assertEnum(schema.at("/properties/violations/items/enum"), Violation.values());
        assertEnum(schema.at("/properties/runtimeStatus/enum"), Status.values());
        assertThat(schema.at("/properties/violations/maxItems").asInt())
                .isEqualTo(Violation.values().length);
        assertThat(schema.at("/properties/laneCount/maximum").asInt())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory
                        .MAXIMUM_LANES);
        assertThat(schema.at("/properties/policy/properties/minimumSamples/maximum").asInt())
                .isEqualTo(1_000_000);
        assertThat(schema.at("/properties/policy/properties/"
                + "maximumLaneFailureBasisPoints/maximum").asInt()).isEqualTo(10_000);
    }

    @Test
    void schemaMakesUnavailableKnownHealthyClosedAndViolationShapesExplicit()
            throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(schemaPath()));

        assertThat(schema.path("allOf")).hasSize(5);
        assertThat(schema.at("/allOf/0/then/properties/runtimeStatus/const").asText())
                .isEqualTo("UNAVAILABLE");
        assertThat(schema.at("/allOf/0/then/properties/pollCount/const").asInt())
                .isEqualTo(-1);
        assertThat(schema.at("/allOf/0/else/properties/pollCount/minimum").asInt())
                .isZero();
        assertThat(schema.at("/allOf/1/then/properties/runtimeStatus/const").asText())
                .isEqualTo("READY");
        assertThat(schema.at("/allOf/2/then/properties/violations/minItems").asInt())
                .isOne();
        assertThat(schema.at("/allOf/3/then/properties/runtimeStatus/const").asText())
                .isEqualTo("RUNTIME_CLOSED");
        assertThat(schema.at("/allOf/4/then/properties/state/const").asText())
                .isEqualTo("OBSERVATION_UNAVAILABLE");
    }

    @Test
    void protocolContainsNoIdentitySecretFailureOrPayloadVocabulary() throws Exception {
        String source = Files.readString(schemaPath());
        for (String forbidden : new String[]{
                "deploymentScopeId", "fleetId", "workerId", "laneKey", "rootSetId",
                "endpoint", "uri", "fingerprint", "privateKey", "credential", "payload",
                "exception", "errorMessage", "stackTrace"}) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
    }

    private static Assessment healthy() {
        return new Assessment(Assessment.SCHEMA_VERSION, State.HEALTHY, List.of(),
                Instant.parse("2026-07-21T12:00:00Z"), Status.READY,
                17, 2, 20, 20, 0, 0, 20, 0, 0,
                20, 0, 0, 1_000, policy().descriptor());
    }

    private static Assessment unavailable() {
        return new Assessment(Assessment.SCHEMA_VERSION, State.OBSERVATION_UNAVAILABLE,
                List.of(Violation.OBSERVATION_UNAVAILABLE), null, Status.UNAVAILABLE,
                -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, policy().descriptor());
    }

    private static Policy policy() {
        return new Policy(Duration.ofSeconds(30), Duration.ofSeconds(30),
                20, 500, 500, 1_000);
    }

    private static Path schemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "external-sequence-anchor-bootstrap-root-recovery-fleet-slo-assessment-v1"
                        + ".schema.json");
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        assertThat(value.properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrderElementsOf(
                        properties.properties().stream()
                                .map(java.util.Map.Entry::getKey).toList());
    }

    private static void assertEnum(JsonNode schemaValues, Enum<?>[] values) {
        assertThat(schemaValues).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(Arrays.stream(values)
                        .map(Enum::name).toArray(String[]::new));
    }
}
