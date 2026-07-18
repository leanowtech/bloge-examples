package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredTestSuiteStabilityAuthorityTrustStoreTest {

    private ObjectMapper objectMapper;
    private KeyPair keyPair;
    private ConfiguredTestSuiteStabilityAuthorityTrustStore trustStore;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        keyPair = keyPair();
        trustStore = new ConfiguredTestSuiteStabilityAuthorityTrustStore(
                objectMapper, AUTHORITY_ID, Duration.ofSeconds(60), Duration.ofSeconds(5),
                Duration.ofMillis(100), List.of(
                new ConfiguredTestSuiteStabilityAuthorityTrustStore.AuthorityKey(
                        KEY_ID, keyPair.getPublic(), null, null, true, false)));
    }

    @Test
    void verifiesExactSignedAuthorizedAndRevokedDecisions() {
        TestSuiteStabilityAuthorityRequest request = request(objectMapper);

        assertThat(trustStore.verify(response(objectMapper, keyPair, request,
                TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED, ""), request, NOW)
                .status()).isEqualTo(
                TestSuiteStabilityAuthorityTrustStore.VerificationStatus.VERIFIED);
        assertThat(trustStore.verify(response(objectMapper, keyPair, request,
                TestSuiteStabilityAuthorityResponse.Decision.REVOKED,
                "RG.POLICY.DELEGATION_REVOKED"), request, NOW).status()).isEqualTo(
                TestSuiteStabilityAuthorityTrustStore.VerificationStatus.VERIFIED);
    }

    @Test
    void rejectsReplayBindingAuthorityKeySignatureAndTimeDrift() {
        TestSuiteStabilityAuthorityRequest request = request(objectMapper);
        String otherChallenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[33]);

        assertStatus(response(objectMapper, keyPair, request,
                        TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED, "",
                        otherChallenge, AUTHORITY_ID, KEY_ID, NOW, NOW.plusSeconds(30)),
                request, TestSuiteStabilityAuthorityTrustStore.VerificationStatus.BINDING_MISMATCH);
        assertStatus(response(objectMapper, keyPair, request,
                        TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED, "",
                        request.challenge(), "other-iam", KEY_ID, NOW, NOW.plusSeconds(30)),
                request, TestSuiteStabilityAuthorityTrustStore.VerificationStatus.AUTHORITY_MISMATCH);
        assertStatus(response(objectMapper, keyPair, request,
                        TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED, "",
                        request.challenge(), AUTHORITY_ID, "unknown-key", NOW, NOW.plusSeconds(30)),
                request, TestSuiteStabilityAuthorityTrustStore.VerificationStatus.KEY_UNAVAILABLE);
        assertStatus(response(objectMapper, keyPair(), request,
                        TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED, ""),
                request, TestSuiteStabilityAuthorityTrustStore.VerificationStatus.SIGNATURE_INVALID);
        assertStatus(response(objectMapper, keyPair, request,
                        TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED, "",
                        request.challenge(), AUTHORITY_ID, KEY_ID,
                        NOW.minusSeconds(10), NOW.plusSeconds(70)),
                request, TestSuiteStabilityAuthorityTrustStore.VerificationStatus.TIME_INVALID);

        ConfiguredTestSuiteStabilityAuthorityTrustStore expiringKey =
                new ConfiguredTestSuiteStabilityAuthorityTrustStore(
                        objectMapper, AUTHORITY_ID, Duration.ofSeconds(60),
                        Duration.ofSeconds(5), Duration.ofMillis(100), List.of(
                        new ConfiguredTestSuiteStabilityAuthorityTrustStore.AuthorityKey(
                                KEY_ID, keyPair.getPublic(), NOW.minusSeconds(30),
                                NOW.plusSeconds(20), true, false)));
        assertThat(expiringKey.verify(response(objectMapper, keyPair, request,
                TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED, ""), request, NOW)
                .status()).isEqualTo(
                TestSuiteStabilityAuthorityTrustStore.VerificationStatus.KEY_UNAVAILABLE);
        assertStatus(response(objectMapper, keyPair, request,
                        TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED, "",
                        request.challenge(), AUTHORITY_ID, KEY_ID,
                        NOW, NOW.plusMillis(50)),
                request, TestSuiteStabilityAuthorityTrustStore.VerificationStatus.TIME_INVALID);
    }

    @Test
    void strictConfigurationRejectsUnknownDuplicatePrivateAndInvalidKeys() throws Exception {
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        String valid = "[{\"keyId\":\"" + KEY_ID
                + "\",\"algorithm\":\"Ed25519\",\"publicKeyBase64\":\""
                + publicKey + "\"}]";

        assertThat(ConfiguredTestSuiteStabilityAuthorityTrustStore.fromJson(
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
    void rejectsDangerousDecisionTimePolicyAtConstruction() {
        var key = new ConfiguredTestSuiteStabilityAuthorityTrustStore.AuthorityKey(
                KEY_ID, keyPair.getPublic(), null, null, true, false);
        assertThatThrownBy(() -> new ConfiguredTestSuiteStabilityAuthorityTrustStore(
                objectMapper, AUTHORITY_ID, Duration.ZERO, Duration.ofSeconds(5),
                Duration.ofMillis(100), List.of(key)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum decision lifetime");
        assertThatThrownBy(() -> new ConfiguredTestSuiteStabilityAuthorityTrustStore(
                objectMapper, AUTHORITY_ID, Duration.ofSeconds(30), Duration.ofSeconds(5),
                Duration.ofSeconds(30), List.of(key)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trust policy");
        assertThatThrownBy(() -> new ConfiguredTestSuiteStabilityAuthorityTrustStore(
                objectMapper, AUTHORITY_ID, Duration.ofSeconds(30), Duration.ofMinutes(6),
                Duration.ofMillis(100), List.of(key)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clock skew");
    }

    private void assertStatus(
            TestSuiteStabilityAuthorityResponse response,
            TestSuiteStabilityAuthorityRequest request,
            TestSuiteStabilityAuthorityTrustStore.VerificationStatus expected) {
        assertThat(trustStore.verify(response, request, NOW).status()).isEqualTo(expected);
    }

    private void assertInvalid(String json) {
        assertThatThrownBy(() -> ConfiguredTestSuiteStabilityAuthorityTrustStore.fromJson(
                objectMapper, AUTHORITY_ID, Duration.ofSeconds(60), Duration.ofSeconds(5),
                Duration.ofMillis(100), json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trust configuration");
    }
}
