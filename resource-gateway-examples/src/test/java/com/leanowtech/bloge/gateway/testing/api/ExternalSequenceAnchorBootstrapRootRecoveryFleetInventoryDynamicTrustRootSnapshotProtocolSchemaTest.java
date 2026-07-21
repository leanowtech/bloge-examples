package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryDynamicTrustRootSnapshotProtocolSchemaTest {

    private static final Set<String> STATUSES = Set.of(
            "HEALTHY", "CLOSED", "REFRESH_UNAVAILABLE", "SOURCE_EXPIRED", "EXPIRED",
            "DEPLOYMENT_THRESHOLD_UNAVAILABLE", "WITNESS_THRESHOLD_UNAVAILABLE");
    private static final Set<String> FAILURE_CODES = Set.of(
            "", "TRUST_ROOT_SOURCE_UNAVAILABLE", "TRUST_ROOT_DOCUMENT_INVALID",
            "TRUST_ROOT_REFRESH_FAILED");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void strictSchemaExactlyMatchesThePublicSnapshotRecord() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(schemaPath()));
        JsonNode properties = schema.path("properties");
        JsonNode value = objectMapper.valueToTree(snapshot("HEALTHY", "", true, false));

        assertThat(value.properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrderElementsOf(
                        properties.properties().stream().map(java.util.Map.Entry::getKey).toList());
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("required")).hasSize(properties.size());
        assertThat(schema.at("/properties/schemaVersion/const").asText())
                .isEqualTo(DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                        .Snapshot.SCHEMA_VERSION);
    }

    @Test
    void schemaFreezesStatusFailureAndOperationalBounds() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(schemaPath()));

        assertThat(schema.at("/properties/status/enum"))
                .extracting(JsonNode::asText).containsExactlyInAnyOrderElementsOf(STATUSES);
        assertThat(schema.at("/properties/lastFailureCode/enum"))
                .extracting(JsonNode::asText).containsExactlyInAnyOrderElementsOf(FAILURE_CODES);
        assertThat(schema.at("/properties/requestTimeoutMillis/minimum").asInt())
                .isEqualTo(100);
        assertThat(schema.at("/properties/requestTimeoutMillis/maximum").asInt())
                .isEqualTo(30_000);
        assertThat(schema.at("/properties/maximumSnapshotAgeSeconds/maximum").asInt())
                .isEqualTo(86_400);
        assertThat(schema.at("/properties/activeWitnessAuthorityCount/maximum").asInt())
                .isEqualTo(32);
        assertThat(schema.path("allOf")).hasSize(3);
    }

    @Test
    void JavaAndSchemaRejectContradictoryStateImplications() {
        assertThatThrownBy(() -> snapshot("HEALTHY", "", false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot("REFRESH_UNAVAILABLE", "", false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot("SOURCE_EXPIRED",
                "TRUST_ROOT_SOURCE_UNAVAILABLE", false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot("UNKNOWN", "", false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot("HEALTHY", "", true, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void protocolContainsNoEndpointIdentityKeyFingerprintOrFailureDetail() throws Exception {
        String source = Files.readString(schemaPath());
        for (String forbidden : new String[]{
                "deploymentScopeId", "fleetId", "trustRootSetId", "authorityId", "keyId",
                "endpoint", "uri", "etag", "fingerprint", "publicKey", "privateKey",
                "credential", "payload", "exception", "errorMessage", "stackTrace"}) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
    }

    private static DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
            .Snapshot snapshot(String status, String failureCode, boolean available,
            boolean byzantineWithoutExternal) {
        return new DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                .Snapshot(
                DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                        .Snapshot.SCHEMA_VERSION,
                available, status, 1, Instant.parse("2026-07-21T00:00:00Z"),
                1, failureCode.isBlank() ? 0 : 1, failureCode,
                10, 1_000, 5, 120, 1, 1, 1, 1,
                true, false, byzantineWithoutExternal, false);
    }

    private static Path schemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "external-sequence-anchor-bootstrap-root-recovery-fleet-inventory-"
                        + "dynamic-trust-root-snapshot-v1.schema.json");
    }
}
