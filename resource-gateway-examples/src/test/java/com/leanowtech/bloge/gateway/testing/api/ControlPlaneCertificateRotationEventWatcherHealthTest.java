package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneCertificateRotationEventWatcherHealthTest {

    @Test
    void idleAppliedAndIntentionalServingFenceAreHealthy() {
        for (String status : new String[]{"IDLE", "APPLIED", "RUNTIME_FENCED"}) {
            var health = new ControlPlaneCertificateRotationEventWatcherHealth(
                    () -> descriptor(status, true), true).health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsEntry("schemaVersion",
                            ControlPlaneCertificateRotationEventWatcherHealth.SCHEMA_VERSION)
                    .containsEntry("enabled", true)
                    .containsEntry("required", true)
                    .containsEntry("ready", true)
                    .containsEntry("durableCursor", true)
                    .containsEntry("authenticatedProtocol", true)
                    .containsEntry("sourceMutualTls", true)
                    .containsEntry("sourceCertificateIdentityBound", true)
                    .containsEntry("status", status);
        }
    }

    @Test
    void newProtocolCursorSourceAndApplyFailuresRemainUnhealthy() {
        for (String status : new String[]{"NEW", "PROTOCOL_REJECTED",
                "CURSOR_UNAVAILABLE", "SOURCE_UNAVAILABLE", "APPLY_BLOCKED"}) {
            var health = new ControlPlaneCertificateRotationEventWatcherHealth(
                    () -> descriptor(status, false), false).health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry("required", false)
                    .containsEntry("ready", false)
                    .containsEntry("status", status);
        }
    }

    @Test
    void descriptorOutageIsPayloadFreeAndFixedCardinality() {
        var health = new ControlPlaneCertificateRotationEventWatcherHealth(
                () -> { throw new IllegalStateException(
                        "https://ca.internal/events?token=secret"); }, true).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .hasSize(12)
                .containsEntry("status", "UNAVAILABLE")
                .containsEntry("reasonCode", "WATCHER_DESCRIPTOR_UNAVAILABLE")
                .containsEntry("authenticatedProtocol", false)
                .containsEntry("sourceMutualTls", false)
                .containsEntry("sourceCertificateIdentityBound", false);
        assertThat(health.toString()).doesNotContain(
                "ca.internal", "token", "secret", "https://");
    }

    private static ControlPlaneCertificateRotationEventWatcher.Descriptor descriptor(
            String status,
            boolean ready) {
        return new ControlPlaneCertificateRotationEventWatcher.Descriptor(
                ControlPlaneCertificateRotationEventWatcher.Descriptor.SCHEMA_VERSION,
                ready, true, true, true, true, true,
                9, false, 2, 3, status, reason(status));
    }

    private static String reason(String status) {
        return switch (status) {
            case "IDLE" -> "NO_EVENTS";
            case "APPLIED" -> "PAGE_APPLIED";
            case "RUNTIME_FENCED" -> "RUNTIME_SERVING_FENCED";
            case "NEW" -> "NOT_POLLED";
            case "PROTOCOL_REJECTED" -> "EVENT_SOURCE_PAGE_INVALID";
            case "CURSOR_UNAVAILABLE" -> "EVENT_CURSOR_UNAVAILABLE";
            case "SOURCE_UNAVAILABLE" -> "EVENT_SOURCE_UNAVAILABLE";
            case "APPLY_BLOCKED" -> "CERTIFICATE_ROTATION_GENERATION_CONFLICT";
            default -> throw new IllegalArgumentException("unsupported test state");
        };
    }
}
