package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MirrorDeploymentIsolationProtocolSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void fixedFixtureRoundTripsAndVerifiesWithTheProducerImplementation() throws Exception {
        JsonNode fixture = fixture();
        JsonNode key = fixture.path("verificationKey");
        MirrorDeploymentIsolationAttestation attestation = objectMapper.treeToValue(
                fixture.path("attestation"), MirrorDeploymentIsolationAttestation.class);
        MirrorDeploymentIsolationAttestation.DeploymentIdentity expected =
                objectMapper.treeToValue(fixture.path("expectedDeployment"),
                        MirrorDeploymentIsolationAttestation.DeploymentIdentity.class);
        var authority = new MirrorDeploymentIsolationAttestationIntegrity.AuthorityKey(
                key.path("keyId").asText(), key.path("algorithm").asText(),
                key.path("encodedPublicKey").asText(), key.path("issuer").asText(),
                Instant.parse(key.path("notBefore").asText()),
                Instant.parse(key.path("notAfter").asText()),
                MirrorDeploymentIsolationAttestationIntegrity.KeyState.valueOf(
                        key.path("state").asText()));

        var verification = new MirrorDeploymentIsolationAttestationIntegrity(objectMapper).verify(
                attestation, authority, expected,
                Instant.parse(fixture.at("/executionWindow/startedAt").asText()),
                Instant.parse(fixture.at("/executionWindow/completedAt").asText()));

        assertThat(verification.verified()).isTrue();
        assertThat(verification.reasonCode()).isEqualTo("VERIFIED");
        assertThat(attestation.artifactRef()).isEqualTo(new MirrorArtifactRef(
                "DEPLOYMENT_ISOLATION_ATTESTATION", "mirror-staging-isolation", 7,
                "sha256:df9059a0cd8f2dcc498a0fede82f64ae76f5b13a0c097fc47611e8d4bd4ec098"));
        assertThat(objectMapper.writeValueAsString(attestation))
                .isEqualTo(objectMapper.writeValueAsString(fixture.path("attestation")));
    }

    @Test
    void strictSchemaExactlyMatchesEverySerializedProtocolRecord() throws Exception {
        JsonNode schema = schema();
        JsonNode attestation = fixture().path("attestation");

        assertProperties(attestation, schema.path("properties"));
        assertProperties(attestation.path("material"),
                schema.at("/$defs/material/properties"));
        assertProperties(attestation.at("/material/deployment"),
                schema.at("/$defs/deployment/properties"));
        assertProperties(attestation.at("/material/enforcement"),
                schema.at("/$defs/enforcement/properties"));
        assertProperties(attestation.at("/material/enforcement/proofRefs/0"),
                schema.at("/$defs/artifactRef/properties"));
        assertProperties(attestation.path("seal"), schema.at("/$defs/seal/properties"));

        for (String pointer : Set.of("", "/$defs/material", "/$defs/deployment",
                "/$defs/enforcement", "/$defs/artifactRef", "/$defs/seal")) {
            assertThat(schema.at(pointer + "/additionalProperties").asBoolean(true)).isFalse();
            assertThat(fieldNames(schema.at(pointer + "/properties")))
                    .containsExactlyInAnyOrderElementsOf(
                            textValues(schema.at(pointer + "/required")));
        }
    }

    @Test
    void failClosedPolicyBoundsAndPayloadExclusionsAreFrozen() throws Exception {
        JsonNode schema = schema();

        for (String field : Set.of("failClosed", "defaultDenyEgress",
                "externalBusinessEgressDenied", "productionCredentialsDenied",
                "productionIdentityDenied", "continuousEnforcement")) {
            assertThat(schema.at("/$defs/enforcement/properties/" + field + "/const")
                    .asBoolean()).isTrue();
        }
        assertThat(schema.at("/$defs/enforcement/properties/enforcementLayers/maxItems")
                .asInt()).isEqualTo(8);
        assertThat(schema.at("/$defs/enforcement/properties/proofRefs/maxItems")
                .asInt()).isEqualTo(32);
        assertThat(schema.at("/$defs/canonicalInstant/pattern").asText()).endsWith("Z$");
        String source = Files.readString(schemaPath());
        for (String forbidden : Set.of("requestPayload", "responsePayload", "credential",
                "secret", "token", "password", "stackTrace", "endpointUri")) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
    }

    private JsonNode fixture() throws Exception {
        return objectMapper.readTree(Files.readString(fixturePath()));
    }

    private JsonNode schema() throws Exception {
        return objectMapper.readTree(Files.readString(schemaPath()));
    }

    private static Path fixturePath() {
        return protocolPath("mirror-deployment-isolation-stage1-v1.fixture.json");
    }

    private static Path schemaPath() {
        return protocolPath("mirror-deployment-isolation-attestation-v1.schema.json");
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
