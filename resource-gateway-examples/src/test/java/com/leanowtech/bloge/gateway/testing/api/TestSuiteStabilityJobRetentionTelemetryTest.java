package com.leanowtech.bloge.gateway.testing.api;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TestSuiteStabilityJobRetentionTelemetryTest {

    @Test
    void exposesOnlyClosedAttemptTagsAndAggregateLifecycleGauges() {
        var meters = new SimpleMeterRegistry();
        var telemetry = new TestSuiteStabilityJobRetentionTelemetry(meters);
        var completed = TestSuiteStabilityJobRetentionAttempt.completed(
                new TestSuiteStabilityJobRetentionResult(4, 3, now()));
        TestSuiteStabilityJobRetentionSnapshot snapshot = backloggedSnapshot();

        telemetry.record(completed, Duration.ofMillis(10));
        telemetry.record(TestSuiteStabilityJobRetentionAttempt.leaseBusy(),
                Duration.ofMillis(5));
        telemetry.recordFailure(Duration.ofMillis(2));
        telemetry.observeSlo(snapshot,
                TestSuiteStabilityJobRetentionSloMonitor.State.SLO_VIOLATED,
                Duration.ofMinutes(4), Duration.ofHours(2), Duration.ofHours(3));

        String prefix =
                "resource.gateway.test.runtime.suite.stability.jobs.retention.";
        assertThat(meters.get(prefix + "attempts").tag("result", "completed")
                .counter().count()).isEqualTo(1);
        assertThat(meters.get(prefix + "attempts").tag("result", "lease_busy")
                .counter().count()).isEqualTo(1);
        assertThat(meters.get(prefix + "attempts").tag("result", "failed")
                .counter().count()).isEqualTo(1);
        assertThat(meters.get(prefix + "duration").timer().count()).isEqualTo(3);
        assertThat(meters.get(prefix + "jobs.tombstoned.total")
                .gauge().value()).isEqualTo(4);
        assertThat(meters.get(prefix + "tombstones.purged.total")
                .gauge().value()).isEqualTo(3);
        assertThat(meters.get(prefix + "jobs.records").gauge().value()).isEqualTo(5);
        assertThat(meters.get(prefix + "tombstones.records").gauge().value()).isEqualTo(3);
        assertThat(meters.get(prefix + "jobs.overdue").gauge().value()).isEqualTo(2);
        assertThat(meters.get(prefix + "tombstones.expired").gauge().value()).isEqualTo(1);
        assertThat(meters.get(prefix + "last.success.age")
                .gauge().value()).isEqualTo(240);
        assertThat(meters.get(prefix + "jobs.overdue.oldest.age")
                .gauge().value()).isEqualTo(7_200);
        assertThat(meters.get(prefix + "tombstones.expired.oldest.age")
                .gauge().value()).isEqualTo(10_800);
        assertThat(meters.get(prefix + "health").gauge().value()).isEqualTo(-1);
        assertThat(meters.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag ->
                        assertThat(tag.getKey()).isEqualTo("result")));
    }

    private static Instant now() {
        return Instant.parse("2026-07-18T08:00:00Z");
    }

    private static TestSuiteStabilityJobRetentionSnapshot backloggedSnapshot() {
        Instant now = now();
        return new TestSuiteStabilityJobRetentionSnapshot(
                "", 1, Instant.EPOCH, 2, 4, 3,
                5, 3, 2, 1,
                now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(3)),
                now.minus(Duration.ofMinutes(4)), now);
    }
}
