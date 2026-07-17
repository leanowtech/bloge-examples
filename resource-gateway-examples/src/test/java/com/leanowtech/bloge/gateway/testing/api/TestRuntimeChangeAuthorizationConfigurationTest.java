package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRuntimeChangeAuthorizationConfigurationTest {

    private static final String POLICY = "sha256:" + "a".repeat(64);

    @Test
    void absentConfigurationIsExplicitlyUnavailableAndPartialConfigurationFailsClosed()
            throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        TestRuntimeConfiguration configuration = new TestRuntimeConfiguration();

        WorkerQuarantineChangeAuthorizationTrustStore unavailable =
                configuration.workerQuarantineChangeAuthorizationTrustStore(
                        objectMapper, "", "", 0, "");

        assertThat(unavailable.descriptor().available()).isFalse();
        assertThat(unavailable.descriptor().properties()).doesNotContainKeys(
                "publicKey", "privateKey", "publicKeyBase64");
        assertThatThrownBy(() ->
                configuration.workerQuarantineChangeAuthorizationTrustStore(
                        objectMapper, "governance.example", "", 0, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trust configuration");
    }

    @Test
    void completePublicKeyConfigurationPublishesOnlyKeyFreeReadiness() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String keys = objectMapper.writeValueAsString(List.of(Map.of(
                "authorityId", "authority-a",
                "keyId", "key-a",
                "publicKeyBase64", Base64.getEncoder().encodeToString(
                        keyPair.getPublic().getEncoded()),
                "notBefore", "2026-01-01T00:00:00Z",
                "expiresAt", "2027-01-01T00:00:00Z",
                "enabled", true,
                "revoked", false)));

        WorkerQuarantineChangeAuthorizationTrustStore configured =
                new TestRuntimeConfiguration().workerQuarantineChangeAuthorizationTrustStore(
                        objectMapper, "governance.example", POLICY, 1, keys);

        assertThat(configured.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isTrue();
            assertThat(descriptor.trustDomain()).isEqualTo("governance.example");
            assertThat(descriptor.authorityCount()).isOne();
            assertThat(descriptor.keyCount()).isOne();
            assertThat(descriptor.signatureThreshold()).isOne();
            assertThat(descriptor.properties()).containsEntry("algorithm", "Ed25519")
                    .doesNotContainKeys("publicKey", "privateKey", "publicKeyBase64");
            assertThat(descriptor.toString())
                    .doesNotContain(Base64.getEncoder().encodeToString(
                            keyPair.getPublic().getEncoded()));
        });
    }
}
