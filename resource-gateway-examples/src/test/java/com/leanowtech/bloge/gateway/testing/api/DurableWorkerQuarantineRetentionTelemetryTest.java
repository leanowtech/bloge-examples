package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DurableWorkerQuarantineRetentionTelemetryTest {

    @Test
    void exposesOnlyClosedAttemptTagsAndAggregateLifecycleGauges() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DurableWorkerQuarantineRetentionTelemetry telemetry =
                new DurableWorkerQuarantineRetentionTelemetry(meters);
        var completed = new DatabaseDurableWorkerQuarantineControlPlane.RetentionAttempt(
                DatabaseDurableWorkerQuarantineControlPlane.RetentionStatus.COMPLETED,
                new DatabaseDurableWorkerQuarantineControlPlane.RetentionResult(
                        4, 2, 3, Instant.parse("2026-07-17T06:00:00Z")));
        var busy = new DatabaseDurableWorkerQuarantineControlPlane.RetentionAttempt(
                DatabaseDurableWorkerQuarantineControlPlane.RetentionStatus.LEASE_BUSY, null);
        var snapshot = new DatabaseDurableWorkerQuarantineControlPlane.RetentionSnapshot(
                "replica-a", 7, Instant.parse("2026-07-17T06:02:00Z"), 11,
                40, 20, 30, 8, Instant.parse("2026-07-17T06:00:00Z"),
                Instant.parse("2026-07-17T06:00:00Z"));

        telemetry.record(completed, Duration.ofMillis(10));
        telemetry.record(busy, Duration.ofMillis(5));
        telemetry.recordFailure(Duration.ofMillis(2));
        telemetry.refresh(snapshot);

        String prefix =
                "resource.gateway.test.runtime.worker.candidate.quarantines.retention.";
        assertThat(meters.get(prefix + "attempts").tag("result", "completed")
                .counter().count()).isEqualTo(1);
        assertThat(meters.get(prefix + "attempts").tag("result", "lease_busy")
                .counter().count()).isEqualTo(1);
        assertThat(meters.get(prefix + "attempts").tag("result", "failed")
                .counter().count()).isEqualTo(1);
        assertThat(meters.get(prefix + "duration").timer().count()).isEqualTo(3);
        assertThat(meters.get(prefix + "tombstoned.total").gauge().value()).isEqualTo(40);
        assertThat(meters.get(prefix + "tombstones.purged.total").gauge().value())
                .isEqualTo(20);
        assertThat(meters.get(prefix + "history.purged.total").gauge().value())
                .isEqualTo(30);
        assertThat(meters.get(prefix + "tombstones.records").gauge().value()).isEqualTo(8);
        assertThat(meters.get(prefix + "last.success.epoch").gauge().value())
                .isEqualTo(Instant.parse("2026-07-17T06:00:00Z").getEpochSecond());
        assertThat(meters.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag ->
                        assertThat(tag.getKey()).isEqualTo("result")));
    }
}
