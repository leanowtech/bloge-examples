package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadOnlyShadowSourceResolutionCompatibilityFixtureTest {
    private static final String FIXTURE =
            "read-only-shadow-source-resolution-stage1-v1.fixture.json";
    private final ObjectMapper mapper =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(
                            SerializationFeature
                                    .WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void serverRehydratesAndVerifiesTheSameThreeAuthorityFixtureAsTestKit()
            throws Exception {
        JsonNode fixture = mapper.readTree(
                Files.readString(fixturePath()));
        assertThat(fixture.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrderElementsOf(
                        Set.of(
                                "schemaVersion",
                                "verificationTime",
                                "verificationKeys",
                                "expected",
                                "candidateEvidenceBundle",
                                "sourceBinding",
                                "attestation"));
        assertThat(fixture.path("schemaVersion").asText())
                .isEqualTo(
                        "resourceGateway.readOnlyShadowSourceResolutionCompatibility.v1");
        Instant verificationTime =
                Instant.parse(
                        fixture.path("verificationTime")
                                .asText());
        JsonNode keys = fixture.path("verificationKeys");
        VisualEvidenceSigner candidateVerifier =
                publicKeyVerifier(
                        keys.path("candidateEvidence"));
        VisualEvidenceSigner bindingVerifier =
                publicKeyVerifier(
                        keys.path("sourceBinding"));
        VisualEvidenceSigner resolutionVerifier =
                publicKeyVerifier(
                        keys.path("sourceResolution"));

        MirrorEvidenceBundle candidate =
                mapper.treeToValue(
                        fixture.path("candidateEvidenceBundle"),
                        MirrorEvidenceBundle.class);
        ReadOnlyShadowSourceBinding binding =
                mapper.treeToValue(
                        fixture.path("sourceBinding"),
                        ReadOnlyShadowSourceBinding.class);
        ReadOnlyShadowSourceResolutionAttestation
                attestation = mapper.treeToValue(
                fixture.path("attestation"),
                ReadOnlyShadowSourceResolutionAttestation.class);
        Clock verificationClock =
                Clock.fixed(
                        verificationTime,
                        ZoneOffset.UTC);

        assertThat(new MirrorEvidenceIntegrityService(
                mapper,
                candidateVerifier,
                verificationClock).verify(candidate))
                .isEqualTo(
                        MirrorEvidenceIntegrityService
                                .Verification.VERIFIED);
        assertThat(new ReadOnlyShadowSourceBindingIntegrity(
                mapper,
                bindingVerifier,
                verificationClock).verify(binding))
                .isEqualTo(binding);
        assertThat(
                new ReadOnlyShadowSourceResolutionAttestationIntegrity(
                        mapper,
                        resolutionVerifier,
                        verificationClock)
                        .verify(attestation))
                .isEqualTo(attestation);
        assertThat(attestation.artifactRef())
                .isEqualTo(
                        mapper.treeToValue(
                                fixture.at(
                                        "/expected/attestationRef"),
                                MirrorArtifactRef.class));
        assertThat(attestation.sourceBindingRef())
                .isEqualTo(binding.artifactRef());
        assertThat(binding.candidateEvidenceRef())
                .isEqualTo(new MirrorArtifactRef(
                        "MIRROR_EVIDENCE_BUNDLE",
                        candidate.evidence().runId(),
                        1,
                        candidate.bundleFingerprint()));

        assertThat(new MirrorEvidenceIntegrityService(
                mapper,
                bindingVerifier,
                verificationClock).verify(candidate))
                .isEqualTo(
                        MirrorEvidenceIntegrityService
                                .Verification.INVALID);
        assertThatThrownBy(() ->
                new ReadOnlyShadowSourceBindingIntegrity(
                        mapper,
                        resolutionVerifier,
                        verificationClock)
                        .verify(binding))
                .isInstanceOf(
                        ReadOnlyShadowSourceBindingIntegrity
                                .Violation.class);
        assertThatThrownBy(() ->
                new ReadOnlyShadowSourceResolutionAttestationIntegrity(
                        mapper,
                        candidateVerifier,
                        verificationClock)
                        .verify(attestation))
                .isInstanceOf(
                        ReadOnlyShadowSourceResolutionAttestationIntegrity
                                .Violation.class);
    }

    @Test
    void fixtureContainsNoPrivateKeyOrBusinessPayload()
            throws Exception {
        String fixture = Files.readString(fixturePath());

        assertThat(fixture)
                .doesNotContainIgnoringCase("privateKey")
                .doesNotContain("\"requestBody\"")
                .doesNotContain("\"responseBody\"")
                .doesNotContain("\"payload\"")
                .contains("\"payloadPolicy\" : \"HASH_ONLY\"");
    }

    private static Path fixturePath() {
        return Path.of(
                "..",
                "docs",
                "schemas",
                "resource-gateway-mirror",
                FIXTURE);
    }

    private static VisualEvidenceSigner publicKeyVerifier(
            JsonNode key) throws Exception {
        java.security.PublicKey publicKey =
                KeyFactory.getInstance("Ed25519")
                        .generatePublic(
                                new X509EncodedKeySpec(
                                        Base64.getDecoder().decode(
                                                key.path(
                                                        "encodedPublicKey")
                                                        .asText())));
        String expectedKeyId = key.path("keyId").asText();
        return new VisualEvidenceSigner() {
            @Override
            public VisualRunEvidenceSeal seal(
                    String materialFingerprint) {
                throw new UnsupportedOperationException(
                        "compatibility fixture verifier cannot sign");
            }

            @Override
            public Verification verify(
                    VisualRunEvidenceSeal seal,
                    String actualMaterialFingerprint) {
                if (!expectedKeyId.equals(seal.keyId())) {
                    return new Verification(
                            false,
                            "INVALID",
                            "fixture key role mismatch");
                }
                try {
                    Signature signature =
                            Signature.getInstance("Ed25519");
                    signature.initVerify(publicKey);
                    signature.update(
                            actualMaterialFingerprint
                                    .getBytes(
                                            StandardCharsets.UTF_8));
                    return new Verification(
                            signature.verify(
                                    Base64.getDecoder()
                                            .decode(
                                                    seal.signature())),
                            "VERIFIED",
                            "");
                } catch (Exception failure) {
                    return new Verification(
                            false,
                            "INVALID",
                            "fixture verification failed");
                }
            }

            @Override
            public Optional<VerificationKey> key(
                    String keyId) {
                return Optional.empty();
            }

            @Override
            public boolean available() {
                return true;
            }
        };
    }
}
