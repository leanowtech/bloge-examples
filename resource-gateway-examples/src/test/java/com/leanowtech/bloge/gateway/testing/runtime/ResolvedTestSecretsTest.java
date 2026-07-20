package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSecretResolutionContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResolvedTestSecretsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void verifiesExactContextAndReturnsPayloadFreeStableBinding() throws Exception {
        TestSecretResolutionContext context = context(Map.of(
                "payment-key", "vault://test/payments/key@v3"));
        ResolvedTestSecrets resolved = resolved(context, "test-payment-secret-47", "version-3");

        ResolvedTestSecrets verified = ResolvedTestSecrets.verified(
                MAPPER, resolved, context, NOW);

        assertThat(verified.resolve("payment-key")).isEqualTo("test-payment-secret-47");
        String projection = MAPPER.writeValueAsString(Map.of(
                "binding", verified.configurationFingerprint(MAPPER),
                "dependencies", verified.planDependencies(MAPPER)));
        assertThat(projection)
                .doesNotContain("test-payment-secret-47", "payment-key",
                        "vault://test/payments/key@v3", "version-3")
                .contains("sha256:");
    }

    @Test
    void rejectsCrossScopeSubstitutionVersionDriftExpiryAndUnknownAliasWithoutEchoingValue() {
        TestSecretResolutionContext expected = context(Map.of(
                "payment-key", "vault://test/payments/key@v3"));
        TestSecretResolutionContext otherScope = new TestSecretResolutionContext("",
                "tenant-b", expected.organizationId(), expected.projectId(),
                expected.environmentId(), expected.region(), expected.actorType(),
                expected.actorId(), expected.delegatedBy(), expected.purpose(),
                expected.groups(), expected.clearance(), expected.delegationGrantId(),
                expected.authorizedPurpose(), expected.executionTargetFingerprint(),
                expected.fixtureTargetFingerprint(), expected.fixtureBundleId(),
                expected.fixtureRevision(), expected.fixtureFingerprint(), expected.secretRefs());

        assertInvalid(() -> ResolvedTestSecrets.verified(MAPPER,
                resolved(otherScope, "test-payment-secret-47", "version-3"), expected, NOW));
        ResolvedTestSecrets invalidBinding = new ResolvedTestSecrets("",
                expected.fingerprint(MAPPER), "test-secret-authority", "generation-7",
                NOW, NOW.plusSeconds(60), Map.of("payment-key",
                new ResolvedTestSecrets.Secret("payment-key",
                        "vault://test/payments/key@v3", "version-3",
                        fingerprint('d'), "test-payment-secret-47")));
        assertInvalid(() -> ResolvedTestSecrets.verified(
                MAPPER, invalidBinding, expected, NOW));
        assertThat(resolved(expected, "test-payment-secret-47", "version-4")
                .configurationFingerprint(MAPPER))
                .isNotEqualTo(resolved(expected, "test-payment-secret-47", "version-3")
                        .configurationFingerprint(MAPPER));
        ResolvedTestSecrets expired = new ResolvedTestSecrets("",
                expected.fingerprint(MAPPER), "test-secret-authority", "generation-7",
                NOW.minusSeconds(20), NOW.minusSeconds(1), Map.of("payment-key",
                secret(expected, "version-3", "test-payment-secret-47")));
        assertInvalid(() -> ResolvedTestSecrets.verified(MAPPER, expired, expected, NOW));
        assertThatThrownBy(() -> resolved(expected, "test-payment-secret-47", "version-3")
                .resolve("unknown-secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("unknown-secret")
                .hasMessageNotContaining("test-payment-secret-47");
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("test-payment-secret-47");
    }

    private static TestSecretResolutionContext context(Map<String, String> refs) {
        return new TestSecretResolutionContext("", "tenant-a", "org-a", "project-a",
                "test", "sg", "SERVICE", "runner", "", "TEST_EXECUTION",
                Set.of("test.suite.execute"), "RESTRICTED", "grant-a",
                "GRAPH_CONTRACT_TEST", fingerprint('a'), fingerprint('b'),
                "fixture-a", 3, fingerprint('c'), refs);
    }

    private static ResolvedTestSecrets resolved(TestSecretResolutionContext context,
                                                String value, String version) {
        return new ResolvedTestSecrets("", context.fingerprint(MAPPER),
                "test-secret-authority", "generation-7", NOW, NOW.plusSeconds(60),
                Map.of("payment-key", secret(context, version, value)));
    }

    private static ResolvedTestSecrets.Secret secret(TestSecretResolutionContext context,
                                                     String version, String value) {
        String alias = "payment-key";
        String reference = "vault://test/payments/key@v3";
        return new ResolvedTestSecrets.Secret(alias, reference, version,
                ResolvedTestSecrets.bindingFingerprint(MAPPER, context.fingerprint(MAPPER),
                        "test-secret-authority", "generation-7", alias, reference, version),
                value);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
