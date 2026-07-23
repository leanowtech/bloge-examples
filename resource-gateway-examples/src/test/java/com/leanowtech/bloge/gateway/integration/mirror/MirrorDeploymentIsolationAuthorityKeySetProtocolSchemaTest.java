package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MirrorDeploymentIsolationAuthorityKeySetProtocolSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void fixedPublicOnlyFixtureRoundTripsAndVerifiesWithProducerImplementation()
            throws Exception {
        JsonNode fixture = fixture();
        JsonNode expected = fixture.path("expectedBinding");
        var publication = mapper.treeToValue(fixture.path("publication"),
                MirrorDeploymentIsolationAuthorityKeySetPublication.class);
        var binding = new MirrorDeploymentIsolationAuthorityKeySetIntegrity.ExpectedBinding(
                mapper.treeToValue(expected.path("scope"), CapabilitySnapshot.Scope.class),
                mapper.treeToValue(expected.path("deployment"),
                        MirrorDeploymentIsolationAttestation.DeploymentIdentity.class),
                expected.path("attestationIssuer").asText(),
                expected.path("keySetId").asText(),
                expected.path("rootTrustDomain").asText(),
                expected.path("rootThreshold").asInt(),
                textValues(expected.path("acceptedPolicyFingerprints")).stream().toList());
        List<MirrorDeploymentIsolationAuthorityKeySetIntegrity.RootVerificationKey> roots =
                new ArrayList<>();
        for (JsonNode root : fixture.path("bootstrapRoots")) {
            roots.add(new MirrorDeploymentIsolationAuthorityKeySetIntegrity.RootVerificationKey(
                    root.path("authorityId").asText(), root.path("keyId").asText(),
                    root.path("algorithm").asText(), root.path("encodedPublicKey").asText(),
                    Instant.parse(root.path("notBefore").asText()),
                    Instant.parse(root.path("notAfter").asText()),
                    MirrorDeploymentIsolationAuthorityKeySetIntegrity.RootKeyState.valueOf(
                            root.path("state").asText())));
        }

        var verification = new MirrorDeploymentIsolationAuthorityKeySetIntegrity(mapper).verify(
                publication, binding, roots, null,
                Instant.parse(fixture.path("verificationTime").asText()));

        assertThat(verification.verified()).isTrue();
        assertThat(verification.authorityKeys()).hasSize(1);
        assertThat(publication.publicationFingerprint()).isEqualTo(
                "sha256:4b47466ed85d6cbeca85993b03190d548035ef63cb4b4756dab74702f3fc5c9b");
        assertThat(mapper.writeValueAsString(publication))
                .isEqualTo(mapper.writeValueAsString(fixture.path("publication")));
    }

    @Test
    void strictSchemaExactlyMatchesEverySerializedProtocolRecord() throws Exception {
        JsonNode schema = schema();
        JsonNode publication = fixture().path("publication");

        assertProperties(publication, schema.path("properties"));
        assertProperties(publication.path("material"), schema.at("/$defs/material/properties"));
        assertProperties(publication.at("/material/scope"), schema.at("/$defs/scope/properties"));
        assertProperties(publication.at("/material/deployment"),
                schema.at("/$defs/deployment/properties"));
        assertProperties(publication.at("/material/authorityKeys/0"),
                schema.at("/$defs/authorityKey/properties"));
        assertProperties(publication.at("/signatures/0"),
                schema.at("/$defs/rootSignature/properties"));

        for (String pointer : Set.of("", "/$defs/material", "/$defs/scope",
                "/$defs/deployment", "/$defs/authorityKey", "/$defs/rootSignature")) {
            assertThat(schema.at(pointer + "/additionalProperties").asBoolean(true)).isFalse();
            assertThat(fieldNames(schema.at(pointer + "/properties")))
                    .containsExactlyInAnyOrderElementsOf(
                            textValues(schema.at(pointer + "/required")));
        }
    }

    @Test
    void thresholdLifetimeChainAndPayloadExclusionBoundsAreFrozen() throws Exception {
        JsonNode schema = schema();

        assertThat(schema.at("/properties/signatures/maxItems").asInt()).isEqualTo(16);
        assertThat(schema.at("/$defs/material/properties/rootThreshold/maximum").asInt())
                .isEqualTo(16);
        assertThat(schema.at("/$defs/material/properties/authorityKeys/maxItems").asInt())
                .isEqualTo(32);
        assertThat(schema.at("/$defs/material/allOf/0/then/properties/"
                + "previousPublicationFingerprint/const").asText()).isEmpty();
        assertThat(schema.at("/$defs/canonicalInstant/pattern").asText()).endsWith("Z$");
        String source = Files.readString(schemaPath());
        for (String forbidden : Set.of("privateKey", "requestPayload", "responsePayload",
                "credential", "secret", "token", "password", "stackTrace", "endpointUri")) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
    }

    private JsonNode fixture() throws Exception {
        return mapper.readTree(Files.readString(fixturePath()));
    }

    private JsonNode schema() throws Exception {
        return mapper.readTree(Files.readString(schemaPath()));
    }

    private static Path fixturePath() {
        return protocolPath(
                "mirror-deployment-isolation-authority-key-set-stage1-v1.fixture.json");
    }

    private static Path schemaPath() {
        return protocolPath(
                "mirror-deployment-isolation-authority-key-set-publication-v1.schema.json");
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
