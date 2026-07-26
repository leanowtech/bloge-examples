package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoritativeOutcomeObservationCompatibilityFixtureTest {
    private static final String FIXTURE =
            "authoritative-outcome-observation-stage1-v1.fixture.json";
    private final ObjectMapper mapper =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(
                            SerializationFeature
                                    .WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void serverRehydratesTheSamePublicFixtureAsTheStandaloneConsumer()
            throws Exception {
        JsonNode fixture = mapper.readTree(
                Files.readString(fixturePath()));
        assertThat(fixture.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrderElementsOf(
                        Set.of(
                                "schemaVersion",
                                "verificationTime",
                                "verificationKey",
                                "observation"));
        assertThat(fixture.path("schemaVersion").asText())
                .isEqualTo(
                        "resourceGateway.authoritativeOutcomeObservationCompatibility.v1");
        Instant verificationTime = Instant.parse(
                fixture.path("verificationTime")
                        .asText());
        JsonNode key = fixture.path("verificationKey");
        AuthoritativeOutcomeObservation observation =
                mapper.treeToValue(
                        fixture.path("observation"),
                        AuthoritativeOutcomeObservation.class);

        observation.verify(mapper);
        assertThat(observation.reconciliation())
                .isEqualTo(
                        AuthoritativeOutcomeObservation
                                .Reconciliation.MATCH);
        assertThat(observation.observationSeal()
                .materialFingerprint())
                .isEqualTo(
                        observation
                                .attestationMaterialFingerprint(
                                        mapper));
        assertThat(observation.attestedAt())
                .isBeforeOrEqualTo(verificationTime);
        assertThat(observation.observationSeal().signedAt())
                .isEqualTo(observation.attestedAt());
        assertThat(verifySignature(
                key,
                observation.observationSeal()
                        .materialFingerprint(),
                observation.observationSeal().signature()))
                .isTrue();
    }

    @Test
    void fixtureContainsNoPrivateKeyCredentialEndpointOrBusinessPayload()
            throws Exception {
        String fixture = Files.readString(fixturePath());

        assertThat(fixture)
                .doesNotContainIgnoringCase("privateKey")
                .doesNotContainIgnoringCase("credential")
                .doesNotContainIgnoringCase("endpoint")
                .doesNotContain("\"request\"")
                .doesNotContain("\"response\"")
                .doesNotContain("\"payload\"");
    }

    private static boolean verifySignature(
            JsonNode key,
            String material,
            String encodedSignature)
            throws Exception {
        java.security.PublicKey publicKey =
                KeyFactory.getInstance("Ed25519")
                        .generatePublic(
                                new X509EncodedKeySpec(
                                        Base64.getDecoder()
                                                .decode(
                                                        key.path(
                                                                "encodedPublicKey")
                                                                .asText())));
        Signature signature =
                Signature.getInstance("Ed25519");
        signature.initVerify(publicKey);
        signature.update(
                material.getBytes(
                        StandardCharsets.UTF_8));
        return signature.verify(
                Base64.getDecoder().decode(
                        encodedSignature));
    }

    private static Path fixturePath() {
        return Path.of(
                "..",
                "docs",
                "schemas",
                "resource-gateway-mirror",
                FIXTURE);
    }
}
