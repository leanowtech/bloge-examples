package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneCertificateRotationProtocolSchemaTest {

    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void strictSchemasExactlyMatchSerializedEventAndResultFields() throws Exception {
        JsonNode eventSchema = schema("control-plane-certificate-rotation-event-v1.schema.json");
        JsonNode resultSchema = schema(
                "control-plane-certificate-rotation-apply-result-v1.schema.json");
        JsonNode floorSchema = schema(
                "control-plane-certificate-rotation-floor-snapshot-v1.schema.json");
        JsonNode acknowledgementSchema = schema(
                "control-plane-certificate-rotation-replica-acknowledgement-v1.schema.json");
        JsonNode convergenceSchema = schema(
                "control-plane-certificate-rotation-convergence-snapshot-v1.schema.json");
        JsonNode monitorSchema = schema(
                "control-plane-certificate-rotation-convergence-monitor-descriptor-v1.schema.json");
        JsonNode runtimeSchema = schema(
                "control-plane-certificate-rotation-runtime-descriptor-v2.schema.json");
        ControlPlaneCertificateRotationEvent event = event();
        var result = new ControlPlaneCertificateRotationController.ApplyResult(
                ControlPlaneCertificateRotationController.ApplyResult.SCHEMA_VERSION,
                ControlPlaneCertificateRotationController.ApplyStatus.APPLIED,
                "APPLIED", "rotation-002", FINGERPRINT, 1, 2);
        Instant now = Instant.parse("2026-07-21T12:00:00Z");
        var floor = new ControlPlaneCertificateRotationFloor.Snapshot(
                ControlPlaneCertificateRotationFloor.Snapshot.SCHEMA_VERSION,
                "rg-staging-sg", "recovery-fleet.publisher", 1, "initial-a",
                FINGERPRINT, "", "", now, 2, "candidate-b", FINGERPRINT,
                "rotation-002", FINGERPRINT, now.plusSeconds(300), now);
        var expectedRotation = new ControlPlaneCertificateRotationConvergenceRepository
                .ExpectedRotation(ControlPlaneCertificateRotationTargets.RECOVERY_FLEET_INVENTORY,
                2, "rotation-002", FINGERPRINT, FINGERPRINT, now.plusSeconds(300));
        var acknowledgement = new ControlPlaneCertificateRotationConvergenceRepository
                .Acknowledgement(
                ControlPlaneCertificateRotationConvergenceRepository.Acknowledgement
                        .SCHEMA_VERSION,
                "rg-staging-sg", "rollout-2026q3", "replica-a",
                UUID.randomUUID().toString(), FINGERPRINT, FINGERPRINT,
                "bloge.rotation.v1", 1, expectedRotation,
                ControlPlaneCertificateRotationConvergenceRepository.ReplicaState.STAGED, "");
        var convergence = new ControlPlaneCertificateRotationConvergenceRepository.Snapshot(
                ControlPlaneCertificateRotationConvergenceRepository.Snapshot.SCHEMA_VERSION,
                true, false, "ACTIVATION_PERMITTED", 2, 2, 2, 2, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, now, now.plusSeconds(30),
                List.of(), List.of("REPLICA_NOT_ACTIVE", "ACTIVE_REPLICA_MISSING"));
        var monitor = new ControlPlaneCertificateRotationConvergenceMonitor.Descriptor(
                ControlPlaneCertificateRotationConvergenceMonitor.Descriptor.SCHEMA_VERSION,
                true, true, false, true, 2, 1, 1, 0, 0,
                "ACTIVATION_PERMITTED");
        var runtime = new ControlPlaneCertificateRotationRuntime.Descriptor(
                ControlPlaneCertificateRotationRuntime.Descriptor.SCHEMA_VERSION,
                true, true, true, true, 2, 2, true,
                true, true, true, true, false, "CONVERGED");

        assertProperties(objectMapper.valueToTree(event),
                eventSchema.at("/$defs/event/properties"));
        assertProperties(objectMapper.valueToTree(event.material()),
                eventSchema.at("/$defs/material/properties"));
        assertProperties(objectMapper.valueToTree(event.signatures().getFirst()),
                eventSchema.at("/$defs/authoritySignature/properties"));
        assertProperties(objectMapper.valueToTree(result), resultSchema.path("properties"));
        assertProperties(objectMapper.valueToTree(floor),
                floorSchema.at("/$defs/snapshot/properties"));
        assertProperties(objectMapper.valueToTree(acknowledgement),
                acknowledgementSchema.at("/$defs/acknowledgement/properties"));
        assertProperties(objectMapper.valueToTree(expectedRotation),
                acknowledgementSchema.at("/$defs/expectedRotation/properties"));
        assertProperties(objectMapper.valueToTree(convergence),
                convergenceSchema.path("properties"));
        assertProperties(objectMapper.valueToTree(monitor), monitorSchema.path("properties"));
        assertProperties(objectMapper.valueToTree(runtime), runtimeSchema.path("properties"));
        assertThat(eventSchema.at("/$defs/event/additionalProperties").asBoolean(true)).isFalse();
        assertThat(eventSchema.at("/$defs/material/additionalProperties").asBoolean(true))
                .isFalse();
        assertThat(eventSchema.at(
                "/$defs/authoritySignature/additionalProperties").asBoolean(true)).isFalse();
        assertThat(resultSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(floorSchema.at("/$defs/snapshot/additionalProperties")
                .asBoolean(true)).isFalse();
        assertThat(acknowledgementSchema.at("/$defs/acknowledgement/additionalProperties")
                .asBoolean(true)).isFalse();
        assertThat(acknowledgementSchema.at("/$defs/expectedRotation/additionalProperties")
                .asBoolean(true)).isFalse();
        assertThat(convergenceSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(monitorSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(runtimeSchema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void schemaVocabulariesAndProtocolVersionsMatchJavaExactly() throws Exception {
        JsonNode eventSchema = schema("control-plane-certificate-rotation-event-v1.schema.json");
        JsonNode resultSchema = schema(
                "control-plane-certificate-rotation-apply-result-v1.schema.json");
        JsonNode floorSchema = schema(
                "control-plane-certificate-rotation-floor-snapshot-v1.schema.json");
        JsonNode acknowledgementSchema = schema(
                "control-plane-certificate-rotation-replica-acknowledgement-v1.schema.json");
        JsonNode convergenceSchema = schema(
                "control-plane-certificate-rotation-convergence-snapshot-v1.schema.json");
        JsonNode monitorSchema = schema(
                "control-plane-certificate-rotation-convergence-monitor-descriptor-v1.schema.json");
        JsonNode runtimeSchema = schema(
                "control-plane-certificate-rotation-runtime-descriptor-v2.schema.json");

        assertThat(eventSchema.at("/$defs/event/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateRotationEvent.SCHEMA_VERSION);
        assertThat(eventSchema.at("/$defs/material/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateRotationEvent.Material.SCHEMA_VERSION);
        assertThat(resultSchema.at("/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateRotationController.ApplyResult.SCHEMA_VERSION);
        assertThat(floorSchema.at(
                "/$defs/snapshot/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateRotationFloor.Snapshot.SCHEMA_VERSION);
        assertThat(acknowledgementSchema.at(
                "/$defs/acknowledgement/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateRotationConvergenceRepository
                        .Acknowledgement.SCHEMA_VERSION);
        assertThat(convergenceSchema.at("/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateRotationConvergenceRepository
                        .Snapshot.SCHEMA_VERSION);
        assertThat(monitorSchema.at("/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateRotationConvergenceMonitor.Descriptor
                        .SCHEMA_VERSION);
        assertThat(runtimeSchema.at("/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateRotationRuntime.Descriptor.SCHEMA_VERSION);
        assertThat(runtimeSchema.at("/properties/productionReady/const").asBoolean())
                .isFalse();
        assertThat(resultSchema.at("/properties/status/enum"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(Arrays.stream(
                                ControlPlaneCertificateRotationController.ApplyStatus.values())
                        .map(Enum::name).toArray(String[]::new));
        assertThat(eventSchema.at("/$defs/event/properties/signatures/maxItems").asInt())
                .isEqualTo(32);
        assertThat(eventSchema.at("/$defs/material/properties/generation/minimum").asLong())
                .isEqualTo(2);
        assertThat(eventSchema.at("/$defs/materialId/pattern").asText())
                .doesNotContain(":", "/", "#");
        assertThat(floorSchema.at("/$defs/materialId/pattern").asText())
                .doesNotContain(":", "/", "#");
        assertThat(acknowledgementSchema.at(
                "/$defs/acknowledgement/properties/state/enum"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(Arrays.stream(
                                ControlPlaneCertificateRotationConvergenceRepository
                                        .ReplicaState.values())
                        .map(Enum::name).toArray(String[]::new));
    }

    @Test
    void applyResultSchemaCannotCarryTlsMaterialOrResolverFailureDetails() throws Exception {
        String source = Files.readString(schemaPath(
                "control-plane-certificate-rotation-apply-result-v1.schema.json"));

        for (String forbidden : new String[]{
                "materialId", "settingsFingerprint", "policyFingerprint", "certificate",
                "privateKey", "password", "secretRef", "keyStore", "trustStore", "path",
                "exception", "errorMessage", "stackTrace"}) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
    }

    @Test
    void floorSnapshotSchemaCannotCarryTlsMaterialLocationsOrCredentials() throws Exception {
        String source = Files.readString(schemaPath(
                "control-plane-certificate-rotation-floor-snapshot-v1.schema.json"));

        for (String forbidden : new String[]{
                "certificate", "privateKey", "password", "secretRef", "keyStore",
                "trustStore", "path", "exception", "errorMessage", "stackTrace"}) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
    }

    @Test
    void runtimeConfigurationSchemaIsStrictAndDoesNotClaimProductionReadiness()
            throws Exception {
        JsonNode schema = schema(
                "control-plane-certificate-rotation-configuration-v1.schema.json");
        String source = Files.readString(schemaPath(
                "control-plane-certificate-rotation-configuration-v1.schema.json"));

        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.path("required")).extracting(JsonNode::asText)
                .contains("enabled", "required", "deployment-scope-id", "trust-domain",
                        "authority-keys-json", "initial-generations-json",
                        "material-catalog-json");
        assertThat(source)
                .contains("backed by a durable generation floor")
                .contains("does not imply replica convergence")
                .contains("private keys are forbidden")
                .doesNotContain("productionReady\": true", "resolved-password");
    }

    @Test
    void replicaProtocolsCannotCarryTlsMaterialLocationsOrProviderDiagnostics()
            throws Exception {
        String source = Files.readString(schemaPath(
                "control-plane-certificate-rotation-replica-acknowledgement-v1.schema.json"))
                + Files.readString(schemaPath(
                "control-plane-certificate-rotation-convergence-snapshot-v1.schema.json"))
                + Files.readString(schemaPath(
                "control-plane-certificate-rotation-convergence-monitor-descriptor-v1.schema.json"));

        for (String forbidden : new String[]{
                "materialId", "certificate", "privateKey", "password", "secretRef",
                "keyStore", "trustStore", "path", "exception", "errorMessage",
                "stackTrace", "instanceIds", "eventIds"}) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
    }

    @Test
    void convergenceConfigurationSchemaFreezesFailClosedProductBounds() throws Exception {
        JsonNode schema = schema(
                "control-plane-certificate-rotation-convergence-configuration-v1.schema.json");
        String source = Files.readString(schemaPath(
                "control-plane-certificate-rotation-convergence-configuration-v1.schema.json"));

        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.path("required")).extracting(JsonNode::asText)
                .contains("enabled", "required", "fleet-id", "instance-id", "startup-id",
                        "artifact-fingerprint", "expected-instance-ids", "activation-mode",
                        "heartbeat-interval-seconds", "lease-duration-seconds",
                        "inventory-source-type", "inventory-revision",
                        "inventory-expires-at");
        assertThat(schema.at("/properties/activation-mode/const").asText())
                .isEqualTo("ALL_REPLICAS");
        assertThat(schema.at("/properties/required-staged-replicas/maximum").asInt())
                .isEqualTo(ControlPlaneCertificateRotationFleetPolicy.maximumReplicas());
        assertThat(source)
                .contains("Multi-replica inventories require an external attestation")
                .contains("quorum activation is intentionally unavailable")
                .doesNotContain("privateKey", "password", "secretRef");
    }

    private ControlPlaneCertificateRotationEvent event() {
        Instant now = Instant.parse("2026-07-21T12:00:00Z");
        var material = new ControlPlaneCertificateRotationEvent.Material(
                ControlPlaneCertificateRotationEvent.Material.SCHEMA_VERSION,
                "enterprise-pki", "rotation-002", "rg-staging-sg",
                "recovery-fleet.publisher", 2, FINGERPRINT, "candidate-b",
                FINGERPRINT, FINGERPRINT, now.minusSeconds(30), now.minusSeconds(20),
                now.plusSeconds(300), now.plusSeconds(3_600));
        var signature = new ControlPlaneCertificateRotationEvent.AuthoritySignature(
                "authority-a", "key-a", "Ed25519", now,
                Base64.getEncoder().encodeToString(new byte[64]));
        return new ControlPlaneCertificateRotationEvent(
                ControlPlaneCertificateRotationEvent.SCHEMA_VERSION,
                material, FINGERPRINT, List.of(signature));
    }

    private JsonNode schema(String file) throws Exception {
        return objectMapper.readTree(Files.readString(schemaPath(file)));
    }

    private static Path schemaPath(String file) {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing", file);
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        properties.fieldNames().forEachRemaining(expected::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }
}
