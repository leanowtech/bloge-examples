package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneCertificateIdentityPolicyTest {

    private static final String PIN = "sha256:" + "a".repeat(64);

    @Test
    void unboundPolicyIsTheOnlyAcceptedPartialShape() {
        assertThat(ControlPlaneCertificateIdentityPolicy.unbound().bound()).isFalse();

        assertThatThrownBy(() -> new ControlPlaneCertificateIdentityPolicy(
                "CN=client", "", Set.of(), "", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateIdentityPolicy(
                "", "spiffe://example.test/client", Set.of(PIN),
                "spiffe://example.test/server", Set.of(PIN)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completePolicyCanonicalizesSubjectUrisAndPinsWithoutExposingSecrets() {
        var policy = new ControlPlaneCertificateIdentityPolicy(
                "CN=Workload, O=Example", "spiffe://EXAMPLE.test/a/../client",
                Set.of(PIN.toUpperCase()), "spiffe://example.test/server", Set.of(PIN));

        assertThat(policy.bound()).isTrue();
        assertThat(policy.expectedClientSubjectDn()).isEqualTo("cn=workload,o=example");
        assertThat(policy.expectedClientUriSan()).isEqualTo("spiffe://EXAMPLE.test/client");
        assertThat(policy.clientIssuerSpkiPins()).containsExactly(PIN);
    }

    @Test
    void malformedUriSubjectAndDuplicateOrOversizedPinsFailClosed() {
        assertThatThrownBy(() -> new ControlPlaneCertificateIdentityPolicy(
                "not-a-dn", "spiffe://example.test/client", Set.of(PIN),
                "spiffe://example.test/server", Set.of(PIN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateIdentityPolicy(
                "CN=client", "https://user@example.test/client", Set.of(PIN),
                "spiffe://example.test/server", Set.of(PIN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateIdentityPolicy(
                "CN=client", "spiffe://example.test/client", Set.of("not-a-pin"),
                "spiffe://example.test/server", Set.of(PIN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateIdentityPolicy(
                "CN=client", "spiffe://example.test/client",
                java.util.stream.IntStream.range(0, 17)
                        .mapToObj(index -> "sha256:" + "%064x".formatted(index))
                        .collect(java.util.stream.Collectors.toSet()),
                "spiffe://example.test/server", Set.of(PIN)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
