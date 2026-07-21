package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneCertificateRotationEventSourcePropertiesTest {

    private static final String BASELINE = "sha256:" + "a".repeat(64);

    @Test
    void enabledPolicyMaterializesExactSourceAndSchedulerBounds() {
        var properties = enabled(transport(true), false);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.required()).isTrue();
        assertThat(properties.baselineSequence()).isEqualTo(7);
        assertThat(properties.pollInterval()).hasSeconds(9);
        assertThat(properties.maximumPagesPerPoll()).isEqualTo(6);
        assertThat(properties.sourceSettings()).satisfies(settings -> {
            assertThat(settings.endpointUri())
                    .isEqualTo("https://events.example.test/v1/certificate-rotations");
            assertThat(settings.requestTimeout()).hasMillis(2_500);
            assertThat(settings.maximumPageBytes()).isEqualTo(64 * 1024);
            assertThat(settings.clockSkew()).hasSeconds(15);
            assertThat(settings.maximumPageLifetime()).hasSeconds(120);
            assertThat(settings.allowInsecureLoopback()).isFalse();
        });
    }

    @Test
    void disabledPolicyIsCanonicalAndRejectsResidualConfiguration() {
        var disabled = ControlPlaneCertificateRotationEventSourceProperties.disabled();

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.transport().configured()).isFalse();
        assertThatThrownBy(disabled::sourceSettings)
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateRotationEventSourceProperties(
                false, false, "https://residual.example.test/events", 0L, "",
                5L, 4, 3_000L, 256 * 1024, 30L, 300L, false,
                RecoveryFleetPublicationTransportProperties.disabled()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateRotationEventSourceProperties(
                false, true, "", 0L, "", 5L, 4, 3_000L,
                256 * 1024, 30L, 300L, false,
                RecoveryFleetPublicationTransportProperties.disabled()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enabledPolicyRejectsWeakTransportAndInvalidPageChainOrIoBounds() {
        assertThatThrownBy(() -> enabled(transport(false), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enabled(systemTrustTransport(), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateRotationEventSourceProperties(
                true, true, "https://events.example.test/events", -1L, BASELINE,
                9L, 6, 2_500L, 64 * 1024, 15L, 120L, false,
                transport(true))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateRotationEventSourceProperties(
                true, true, "https://events.example.test/events", 0L, "sha256:bad",
                9L, 6, 2_500L, 64 * 1024, 15L, 120L, false,
                transport(true))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateRotationEventSourceProperties(
                true, true, "http://events.example.test/events", 0L, BASELINE,
                9L, 6, 2_500L, 64 * 1024, 15L, 120L, true,
                transport(true))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateRotationEventSourceProperties(
                true, true, "https://events.example.test/events", 0L, BASELINE,
                0L, 33, 99L, 513 * 1024, 301L, 86_401L, false,
                transport(true))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void explicitLoopbackEscapeIsNarrowlyAvailableForProtocolTests() {
        var properties = new ControlPlaneCertificateRotationEventSourceProperties(
                true, true, "http://127.0.0.1:18080/events", 0L, BASELINE,
                5L, 1, 1_000L, 8 * 1024, 0L, 30L, true,
                transport(true));

        assertThat(properties.sourceSettings().allowInsecureLoopback()).isTrue();
    }

    private static ControlPlaneCertificateRotationEventSourceProperties enabled(
            RecoveryFleetPublicationTransportProperties transport,
            boolean loopback) {
        return new ControlPlaneCertificateRotationEventSourceProperties(
                true, true,
                loopback ? "http://127.0.0.1:18080/events"
                        : "https://events.example.test/v1/certificate-rotations",
                7L, BASELINE, 9L, 6, 2_500L, 64 * 1024,
                15L, 120L, loopback, transport);
    }

    private static RecoveryFleetPublicationTransportProperties transport(boolean required) {
        String pin = "sha256:" + "b".repeat(64);
        return new RecoveryFleetPublicationTransportProperties(
                true, required, "/deployment/event-source-trust.p12", "env:EVENT_TRUST",
                "/deployment/event-source-client.p12", "env:EVENT_CLIENT",
                pin, true, "CN=resource-gateway-event-source",
                "spiffe://example.test/resource-gateway/event-source", pin,
                "spiffe://example.test/ca/rotation-events", pin);
    }

    private static RecoveryFleetPublicationTransportProperties systemTrustTransport() {
        String pin = "sha256:" + "b".repeat(64);
        return new RecoveryFleetPublicationTransportProperties(
                true, true, "", "",
                "/deployment/event-source-client.p12", "env:EVENT_CLIENT",
                pin, true, "CN=resource-gateway-event-source",
                "spiffe://example.test/resource-gateway/event-source", pin,
                "spiffe://example.test/ca/rotation-events", pin);
    }
}
