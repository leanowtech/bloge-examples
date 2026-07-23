package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MirrorDeploymentIsolationAttestationBundleProtocolSchemaTest {
    private final MirrorDeploymentIsolationAttestationRepositoryTestFixtures fixtures =
            new MirrorDeploymentIsolationAttestationRepositoryTestFixtures();

    @Test
    void generatedActiveAndRevokedBundlesRetainCanonicalNestedFingerprints() {
        var active = fixtures.bundle(7);
        var revokedStatus = fixtures.bundleIntegrity.revokedStatus(active.status(),
                MirrorDeploymentIsolationAttestationStatusPublication.Reason.SECURITY_INCIDENT,
                active.status().material().effectiveAt().plusSeconds(1));
        var revoked = fixtures.bundleIntegrity.bundle(active.scope(), active.authorityKeySetRef(),
                active.attestation(), revokedStatus);

        assertThat(fixtures.bundleIntegrity.canonicalBundleVerified(active)).isTrue();
        assertThat(fixtures.bundleIntegrity.canonicalBundleVerified(revoked)).isTrue();
        assertThat(active.bundleFingerprint()).isNotEqualTo(revoked.bundleFingerprint());
        assertThat(revoked.status().material().previousStatusFingerprint())
                .isEqualTo(active.status().statusFingerprint());
        assertThat(revoked.artifactRef().revision()).isEqualTo(2);
    }

    @Test
    void strictSchemasExactlyMatchEveryNewSerializedRecordBoundary() throws Exception {
        var active = fixtures.bundle(7);
        var request = new MirrorDeploymentIsolationAttestationRevocationRequest("", 7,
                active.attestation().attestationFingerprint(), 1,
                active.status().statusFingerprint(),
                MirrorDeploymentIsolationAttestationStatusPublication.Reason.OPERATOR_REVOKED);
        JsonNode bundle = fixtures.mapper.valueToTree(active);
        JsonNode status = bundle.path("status");
        JsonNode statusSchema = schema(
                "mirror-deployment-isolation-attestation-status-v1.schema.json");
        JsonNode bundleSchema = schema(
                "mirror-deployment-isolation-attestation-bundle-v1.schema.json");
        JsonNode revocationSchema = schema(
                "mirror-deployment-isolation-attestation-revocation-request-v1.schema.json");
        var distributed = fixtures.distributionFixtures();
        var snapshotIntegrity = new MirrorDeploymentIsolationAgentSnapshotIntegrity(
                fixtures.mapper, fixtures.authorityIntegrity, fixtures.bundleIntegrity);
        var snapshot = snapshotIntegrity.snapshot(1, fixtures.activeClock.instant(),
                fixtures.activeClock.instant().plusSeconds(5), distributed.authority(),
                distributed.bundle());
        JsonNode snapshotValue = fixtures.mapper.valueToTree(snapshot);
        JsonNode snapshotSchema = schema(
                "mirror-deployment-isolation-agent-snapshot-v1.schema.json");

        assertProperties(bundle, bundleSchema.path("properties"));
        assertProperties(status, statusSchema.path("properties"));
        assertProperties(status.path("material"),
                statusSchema.at("/$defs/material/properties"));
        assertProperties(status.at("/material/scope"),
                statusSchema.at("/$defs/scope/properties"));
        assertProperties(status.at("/material/deployment"),
                statusSchema.at("/$defs/deployment/properties"));
        assertProperties(status.at("/material/authorityKeySetRef"),
                statusSchema.at("/$defs/authorityRef/properties"));
        assertProperties(status.at("/material/attestationRef"),
                statusSchema.at("/$defs/attestationRef/properties"));
        assertProperties(fixtures.mapper.valueToTree(request),
                revocationSchema.path("properties"));
        assertProperties(snapshotValue, snapshotSchema.path("properties"));
        for (JsonNode closed : Set.of(bundleSchema, statusSchema,
                statusSchema.at("/$defs/material"), statusSchema.at("/$defs/scope"),
                statusSchema.at("/$defs/deployment"), statusSchema.at("/$defs/authorityRef"),
                statusSchema.at("/$defs/attestationRef"), revocationSchema,
                snapshotSchema)) {
            assertThat(closed.path("additionalProperties").asBoolean(true)).isFalse();
        }
    }

    @Test
    void schemasFreezeIrreversibilityBoundsAndPayloadExclusions() throws Exception {
        JsonNode status = schema(
                "mirror-deployment-isolation-attestation-status-v1.schema.json");
        JsonNode revocation = schema(
                "mirror-deployment-isolation-attestation-revocation-request-v1.schema.json");

        assertThat(status.at("/$defs/material/properties/statusRevision/maximum").asInt())
                .isEqualTo(2);
        assertThat(status.at("/$defs/material/oneOf/0/properties/state/const").asText())
                .isEqualTo("ACTIVE");
        assertThat(status.at("/$defs/material/oneOf/1/properties/state/const").asText())
                .isEqualTo("REVOKED");
        assertThat(textValues(revocation.at("/properties/reason/enum")))
                .doesNotContain("ACCEPTED");
        String source = Files.readString(protocolPath(
                "mirror-deployment-isolation-attestation-bundle-v1.schema.json"))
                + Files.readString(protocolPath(
                "mirror-deployment-isolation-attestation-status-v1.schema.json"))
                + Files.readString(protocolPath(
                "mirror-deployment-isolation-attestation-revocation-request-v1.schema.json"))
                + Files.readString(protocolPath(
                "mirror-deployment-isolation-agent-snapshot-v1.schema.json"));
        for (String forbidden : Set.of("requestPayload", "responsePayload", "secret", "token",
                "password", "stackTrace", "endpointUri", "fixtureValue")) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
    }

    private JsonNode schema(String filename) throws Exception {
        return fixtures.mapper.readTree(Files.readString(protocolPath(filename)));
    }

    private static Path protocolPath(String filename) {
        Path moduleRelative = Path.of("..", "docs", "schemas", "resource-gateway-mirror",
                filename);
        return Files.exists(moduleRelative) ? moduleRelative
                : Path.of("docs", "schemas", "resource-gateway-mirror", filename);
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        assertThat(fieldNames(value)).containsExactlyInAnyOrderElementsOf(fieldNames(properties));
    }

    private static LinkedHashSet<String> fieldNames(JsonNode value) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static LinkedHashSet<String> textValues(JsonNode value) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        value.forEach(item -> values.add(item.asText()));
        return values;
    }
}
