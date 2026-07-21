package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneCertificateStatusRuntimePropertiesTest {

    private static final String POLICY = fingerprint('f');
    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void canonicalDisabledPolicyCarriesNoResidualSecurityConfiguration() {
        ControlPlaneCertificateStatusRuntimeProperties properties =
                ControlPlaneCertificateStatusRuntimeProperties.disabled();

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.required()).isFalse();
        assertThat(properties.deploymentScopeId()).isEmpty();
        assertThat(properties.authorityKeysJson()).isEqualTo("[]");
        assertThat(properties.transport().configured()).isFalse();
        assertThatThrownBy(properties::sourceSettings)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enabledPolicyBuildsOnlyBoundedStrictSourceSettings() throws Exception {
        ControlPlaneCertificateStatusRuntimeProperties properties = enabled(keysJson());

        assertThat(properties.sourceSettings()).satisfies(settings -> {
            assertThat(settings.deploymentScopeId()).isEqualTo("rg-staging");
            assertThat(settings.endpointUri()).isEqualTo(
                    "https://certificate-status.example.test/publications");
            assertThat(settings.requestTimeout()).hasSeconds(5);
            assertThat(settings.maximumPublicationBytes()).isEqualTo(512 * 1024);
            assertThat(settings.allowInsecureLoopback()).isFalse();
        });
        assertThat(properties.transport().certificateIdentityBound()).isTrue();
    }

    @Test
    void requiredDisabledResidualPartialAndDuplicatePoliciesFailAtConstruction() throws Exception {
        assertThatThrownBy(() -> properties(false, true, "", "", "", 0,
                "[]", RecoveryFleetPublicationTransportProperties.disabled()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(false, false, "rg-staging", "", "", 0,
                "[]", RecoveryFleetPublicationTransportProperties.disabled()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(true, true, "rg-staging", "enterprise-ca",
                POLICY, 1, keysJson(), RecoveryFleetPublicationTransportProperties.disabled()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(true, true, "rg-staging", "enterprise-ca",
                POLICY + "," + POLICY, 1, keysJson(), transport()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicKeyParserRejectsDuplicateUnknownTrailingAndOversizedConfiguration()
            throws Exception {
        String valid = keysJson();
        String duplicateField = valid.replace("\"keyId\":\"key-a\"",
                "\"keyId\":\"key-a\",\"keyId\":\"key-b\"");
        String unknownField = valid.replace("\"revoked\":false",
                "\"revoked\":false,\"privateKey\":\"forbidden\"");

        assertThatThrownBy(() -> trust(duplicateField, POLICY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> trust(unknownField, POLICY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> trust(valid + "{}", POLICY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> trust(valid, POLICY + "," + POLICY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> trust("[\"" + "x".repeat(512 * 1024) + "\"]", POLICY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactPublicKeyConfigurationCreatesAvailableMaterialFreeTrust() throws Exception {
        ConfiguredControlPlaneCertificateStatusTrustStore trust = trust(keysJson(), POLICY);

        assertThat(trust.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isTrue();
            assertThat(descriptor.authorityCount()).isEqualTo(1);
            assertThat(descriptor.keyCount()).isEqualTo(1);
            assertThat(descriptor.signatureThreshold()).isEqualTo(1);
            assertThat(descriptor.properties())
                    .containsEntry("algorithm", "Ed25519")
                    .containsEntry("privateMaterialPresent", false);
        });
    }

    private ControlPlaneCertificateStatusRuntimeProperties enabled(String keysJson) {
        return properties(true, true, "rg-staging", "enterprise-ca", POLICY, 1,
                keysJson, transport());
    }

    private static ControlPlaneCertificateStatusRuntimeProperties properties(
            boolean enabled,
            boolean required,
            String scope,
            String trustDomain,
            String policies,
            int threshold,
            String keysJson,
            RecoveryFleetPublicationTransportProperties transport) {
        return new ControlPlaneCertificateStatusRuntimeProperties(enabled, required,
                scope, trustDomain, policies, threshold, keysJson,
                0L, fingerprint('0'),
                enabled ? "https://certificate-status.example.test/publications" : "",
                5_000L, 512 * 1024, 60L, 3_600L, 30_000L, 1_000L, 8,
                transport);
    }

    private ConfiguredControlPlaneCertificateStatusTrustStore trust(
            String keysJson, String policies) {
        return ConfiguredControlPlaneCertificateStatusTrustStore.fromJson(objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC), "enterprise-ca", policies, 1, keysJson);
    }

    private String keysJson() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        LinkedHashMap<String, Object> key = new LinkedHashMap<>();
        key.put("authorityId", "authority-a");
        key.put("keyId", "key-a");
        key.put("publicKeyBase64", Base64.getEncoder().encodeToString(
                keyPair.getPublic().getEncoded()));
        key.put("notBefore", NOW.minusSeconds(60).toString());
        key.put("expiresAt", NOW.plusSeconds(3_600).toString());
        key.put("enabled", true);
        key.put("revoked", false);
        return objectMapper.writeValueAsString(java.util.List.of(key));
    }

    private static RecoveryFleetPublicationTransportProperties transport() {
        return new RecoveryFleetPublicationTransportProperties(true, true,
                "/etc/bloge/status-trust.p12", "secret:status-trust",
                "/etc/bloge/status-client.p12", "secret:status-client",
                fingerprint('a'), true, "CN=resource-gateway-status-client",
                "spiffe://example.test/resource-gateway/status-client", fingerprint('b'),
                "spiffe://example.test/certificate-status/server", fingerprint('c'));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
