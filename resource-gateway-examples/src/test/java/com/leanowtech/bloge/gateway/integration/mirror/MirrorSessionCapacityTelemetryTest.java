package com.leanowtech.bloge.gateway.integration.mirror;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MirrorSessionCapacityTelemetryTest {

    @Test
    void publishesOnlyClosedAdmissionSeriesAndGlobalCapacityGauges() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MirrorSessionCapacityTelemetry telemetry =
                new MirrorSessionCapacityTelemetry(meters);

        telemetry.record(
                MirrorSessionCapacityTelemetry.Boundary.REPLICA,
                MirrorSessionCapacityTelemetry.Decision.ADMITTED);
        telemetry.record(
                MirrorSessionCapacityTelemetry.Boundary.DATA_PLANE,
                MirrorSessionCapacityTelemetry.Decision.REJECTED);
        telemetry.commandStarted();
        telemetry.expirySweepCompleted(3);
        telemetry.expirySweepFailed();
        telemetry.expirySweepSkipped();
        telemetry.observe(new MirrorSessionStateStore.CapacitySnapshot(
                7, 4096, 512, 100, 1_000_000));

        assertThat(meters.get(
                "resource.gateway.mirror.session.admission.decisions")
                .tags("boundary", "replica", "decision", "admitted")
                .counter().count()).isEqualTo(1);
        assertThat(meters.get(
                "resource.gateway.mirror.session.admission.decisions")
                .tags("boundary", "data_plane", "decision", "rejected")
                .counter().count()).isEqualTo(1);
        assertThat(meters.get(
                "resource.gateway.mirror.session.commands.inflight")
                .gauge().value()).isEqualTo(1);
        assertThat(meters.get(
                "resource.gateway.mirror.session.capacity.active.sessions")
                .gauge().value()).isEqualTo(7);
        assertThat(meters.get(
                "resource.gateway.mirror.session.capacity.retained.payload.bytes")
                .gauge().value()).isEqualTo(4096);
        assertThat(meters.get(
                "resource.gateway.mirror.session.capacity.expired.retained.payload.bytes")
                .gauge().value()).isEqualTo(512);
        assertThat(meters.get(
                "resource.gateway.mirror.session.expiry.sweeps")
                .tag("outcome", "succeeded")
                .counter().count()).isEqualTo(1);
        assertThat(meters.get(
                "resource.gateway.mirror.session.expiry.last.expired.sessions")
                .gauge().value()).isEqualTo(3);

        telemetry.commandFinished();
        assertThat(meters.get(
                "resource.gateway.mirror.session.commands.inflight")
                .gauge().value()).isZero();

        for (Meter meter : meters.getMeters()) {
            Set<String> tagKeys = meter.getId().getTags().stream()
                    .map(Tag::getKey).collect(Collectors.toSet());
            assertThat(tagKeys).doesNotContain(
                    "tenant", "organization", "project", "environment",
                    "region", "session", "request", "correlation");
        }
    }
}
