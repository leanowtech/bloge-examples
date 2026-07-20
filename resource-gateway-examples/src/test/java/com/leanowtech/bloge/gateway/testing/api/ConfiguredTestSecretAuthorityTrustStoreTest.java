package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityProtocolTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredTestSecretAuthorityTrustStoreTest {

    private ObjectMapper objectMapper;
    private KeyPair keyPair;
    private ConfiguredTestSecretAuthorityTrustStore trustStore;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        keyPair = keyPair();
        trustStore = new ConfiguredTestSecretAuthorityTrustStore(
                objectMapper, AUTHORITY_ID, Duration.ofSeconds(60), Duration.ofSeconds(5),
                Duration.ofMillis(100), List.of(
                new ConfiguredTestSecretAuthorityTrustStore.AuthorityKey(
                        KEY_ID, keyPair.getPublic(), null, null, true, false)));
    }

    @Test
    void verifiesExactSignedAuthorizedAndDeniedResponses() {
        TestSecretAuthorityRequest request = request(objectMapper);

        assertStatus(response(objectMapper, keyPair, request,
                        TestSecretAuthorityResponse.Decision.AUTHORIZED, ""), request,
                TestSecretAuthorityTrustStore.VerificationStatus.VERIFIED);
        assertStatus(response(objectMapper, keyPair, request,
                        TestSecretAuthorityResponse.Decision.DENIED,
                        "RG.POLICY.SECRET_DENIED"), request,
                TestSecretAuthorityTrustStore.VerificationStatus.VERIFIED);
    }

    @Test
    void rejectsReplayAuthorityKeySignatureTimeAndSecretMaterialDrift() {
        TestSecretAuthorityRequest request = request(objectMapper);
        String otherChallenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[33]);

        assertStatus(response(objectMapper, keyPair, request,
                        TestSecretAuthorityResponse.Decision.AUTHORIZED, "", otherChallenge,
                        AUTHORITY_ID, AUTHORITY_GENERATION, KEY_ID, NOW,
                        NOW.plusSeconds(30), VALUE), request,
                TestSecretAuthorityTrustStore.VerificationStatus.BINDING_MISMATCH);
        assertStatus(response(objectMapper, keyPair, request,
                        TestSecretAuthorityResponse.Decision.AUTHORIZED, "", request.challenge(),
                        "other-authority.example", AUTHORITY_GENERATION, KEY_ID, NOW,
                        NOW.plusSeconds(30), VALUE), request,
                TestSecretAuthorityTrustStore.VerificationStatus.AUTHORITY_MISMATCH);
        assertStatus(response(objectMapper, keyPair, request,
                        TestSecretAuthorityResponse.Decision.AUTHORIZED, "", request.challenge(),
                        AUTHORITY_ID, AUTHORITY_GENERATION, "unknown-key", NOW,
                        NOW.plusSeconds(30), VALUE), request,
                TestSecretAuthorityTrustStore.VerificationStatus.KEY_UNAVAILABLE);
        assertStatus(response(objectMapper, keyPair(), request,
                        TestSecretAuthorityResponse.Decision.AUTHORIZED, ""), request,
                TestSecretAuthorityTrustStore.VerificationStatus.SIGNATURE_INVALID);
        assertStatus(response(objectMapper, keyPair, request,
                        TestSecretAuthorityResponse.Decision.AUTHORIZED, "", request.challenge(),
                        AUTHORITY_ID, AUTHORITY_GENERATION, KEY_ID, NOW.minusSeconds(10),
                        NOW.plusSeconds(70), VALUE), request,
                TestSecretAuthorityTrustStore.VerificationStatus.TIME_INVALID);

        TestSecretAuthorityResponse valid = response(objectMapper, keyPair, request,
                TestSecretAuthorityResponse.Decision.AUTHORIZED, "");
        TestSecretAuthorityResponse.SecretMaterial changed =
                new TestSecretAuthorityResponse.SecretMaterial(ALIAS, REFERENCE, VERSION,
                        valid.secrets().get(ALIAS).bindingFingerprint(), "tampered");
        TestSecretAuthorityResponse tampered = new TestSecretAuthorityResponse(
                valid.schemaVersion(), valid.requestId(), valid.challenge(),
                valid.requestFingerprint(), valid.contextFingerprint(), valid.decision(),
                valid.failureCode(), valid.authorityId(), valid.authorityGeneration(),
                valid.decisionId(), valid.issuedAt(), valid.expiresAt(),
                java.util.Map.of(ALIAS, changed), valid.materialFingerprint(), valid.signature());
        assertStatus(tampered, request,
                TestSecretAuthorityTrustStore.VerificationStatus.MATERIAL_INVALID);
    }

    @Test
    void revokedOrExpiringKeysCannotReleaseAClosure() {
        TestSecretAuthorityRequest request = request(objectMapper);
        for (ConfiguredTestSecretAuthorityTrustStore.AuthorityKey key : List.of(
                new ConfiguredTestSecretAuthorityTrustStore.AuthorityKey(
                        KEY_ID, keyPair.getPublic(), null, null, true, true),
                new ConfiguredTestSecretAuthorityTrustStore.AuthorityKey(
                        KEY_ID, keyPair.getPublic(), NOW.minusSeconds(10),
                        NOW.plusSeconds(20), true, false))) {
            ConfiguredTestSecretAuthorityTrustStore unavailable =
                    new ConfiguredTestSecretAuthorityTrustStore(
                            objectMapper, AUTHORITY_ID, Duration.ofSeconds(60),
                            Duration.ofSeconds(5), Duration.ofMillis(100), List.of(key));
            assertThat(unavailable.verify(response(objectMapper, keyPair, request,
                    TestSecretAuthorityResponse.Decision.AUTHORIZED, ""), request, NOW).status())
                    .isEqualTo(TestSecretAuthorityTrustStore.VerificationStatus.KEY_UNAVAILABLE);
        }
    }

    @Test
    void strictConfigurationAcceptsOnlyBoundedPublicEd25519Keys() {
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        String valid = "[{\"keyId\":\"" + KEY_ID
                + "\",\"algorithm\":\"Ed25519\",\"publicKeyBase64\":\""
                + publicKey + "\"}]";

        assertThat(ConfiguredTestSecretAuthorityTrustStore.fromJson(
                objectMapper, AUTHORITY_ID, Duration.ofSeconds(60), Duration.ofSeconds(5),
                Duration.ofMillis(100), valid).descriptor()).satisfies(descriptor -> {
                    assertThat(descriptor.available()).isTrue();
                    assertThat(descriptor.keyCount()).isOne();
                    assertThat(descriptor.properties()).containsEntry("algorithm", "Ed25519")
                            .doesNotContainKeys("publicKey", "publicKeyBase64", "privateKey");
                });
        assertInvalid(valid.replace("}]", ",\"privateKey\":\"secret\"}]"));
        assertInvalid(valid.replace("\"keyId\"", "\"keyId\":\"duplicate\",\"keyId\""));
        assertInvalid(valid.replace("Ed25519", "RS256"));
        assertInvalid("[]");
    }

    @Test
    void rejectsDangerousResponseTimePolicyAtConstruction() {
        var key = new ConfiguredTestSecretAuthorityTrustStore.AuthorityKey(
                KEY_ID, keyPair.getPublic(), null, null, true, false);
        assertThatThrownBy(() -> new ConfiguredTestSecretAuthorityTrustStore(
                objectMapper, AUTHORITY_ID, Duration.ZERO, Duration.ofSeconds(5),
                Duration.ofMillis(100), List.of(key)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum response lifetime");
        assertThatThrownBy(() -> new ConfiguredTestSecretAuthorityTrustStore(
                objectMapper, AUTHORITY_ID, Duration.ofSeconds(30), Duration.ofSeconds(5),
                Duration.ofSeconds(30), List.of(key)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trust policy");
    }

    private void assertStatus(
            TestSecretAuthorityResponse response,
            TestSecretAuthorityRequest request,
            TestSecretAuthorityTrustStore.VerificationStatus expected) {
        assertThat(trustStore.verify(response, request, NOW).status()).isEqualTo(expected);
    }

    private void assertInvalid(String json) {
        assertThatThrownBy(() -> ConfiguredTestSecretAuthorityTrustStore.fromJson(
                objectMapper, AUTHORITY_ID, Duration.ofSeconds(60), Duration.ofSeconds(5),
                Duration.ofMillis(100), json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trust configuration");
    }
}
