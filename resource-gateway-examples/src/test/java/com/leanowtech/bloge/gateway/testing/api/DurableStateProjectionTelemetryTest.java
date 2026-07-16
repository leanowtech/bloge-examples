package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableStateProjectionControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DurableStateProjectionReconciler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DurableStateProjectionTelemetryTest {

    @Test
    void recordsOnlyBoundedResultTagsAndPayloadFreeBacklogGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DurableStateProjectionTelemetry telemetry =
                new DurableStateProjectionTelemetry(registry);
        DurableStateProjectionReconciler.SweepResult sweep =
                new DurableStateProjectionReconciler.SweepResult(
                        DurableStateProjectionReconciler.ScanCursor.start(),
                        5, 2, 1, 1, 0, 0, List.of(), List.of());

        telemetry.recordReconciliation(
                new DatabaseDurableStateProjectionControlPlane.SweepAttempt(
                        DatabaseDurableStateProjectionControlPlane.SweepStatus.COMPLETED, sweep),
                Duration.ofMillis(20));
        telemetry.recordReconciliation(
                DatabaseDurableStateProjectionControlPlane.SweepAttempt.busy(),
                Duration.ofMillis(5));
        telemetry.recordReconciliationFailure(Duration.ofMillis(7));
        telemetry.recordRetention(
                DatabaseDurableStateProjectionControlPlane.RetentionAttempt.completed(
                        new DatabaseDurableStateProjectionControlPlane.RetentionResult(
                                3, 2, Instant.parse("2026-07-17T08:00:00Z"))),
                Duration.ofMillis(30));
        telemetry.recordRetentionFailure(Duration.ofMillis(11));
        telemetry.observe(snapshot(), DurableStateProjectionSloMonitor.State.SLO_VIOLATED,
                Duration.ofSeconds(40), Duration.ofSeconds(80));

        assertThat(counter(registry, "resource.gateway.test.projection.reconciliation.attempts",
                "result", "completed")).isEqualTo(1.0);
        assertThat(counter(registry, "resource.gateway.test.projection.reconciliation.attempts",
                "result", "busy")).isEqualTo(1.0);
        assertThat(counter(registry, "resource.gateway.test.projection.reconciliation.attempts",
                "result", "failed")).isEqualTo(1.0);
        assertThat(counter(registry, "resource.gateway.test.projection.retention.attempts",
                "result", "completed")).isEqualTo(1.0);
        assertThat(counter(registry, "resource.gateway.test.projection.retention.attempts",
                "result", "failed")).isEqualTo(1.0);
        assertThat(registry.get("resource.gateway.test.projection.reconciliation.duration")
                .timer().count()).isEqualTo(3);
        assertThat(registry.get("resource.gateway.test.projection.retention.duration")
                .timer().count()).isEqualTo(2);
        assertThat(registry.get("resource.gateway.test.projection.findings")
                .tag("state", "open").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("resource.gateway.test.projection.findings")
                .tag("state", "claim_expired").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("resource.gateway.test.projection.retention.backlog")
                .tag("tier", "archive").gauge().value()).isEqualTo(5.0);
        assertThat(registry.get("resource.gateway.test.projection.last_success.age")
                .tag("loop", "reconciliation").gauge().value()).isEqualTo(40.0);
        assertThat(registry.get("resource.gateway.test.projection.health").gauge().value())
                .isEqualTo(-1.0);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag ->
                        assertThat(tag.getKey()).isIn("result", "state", "tier", "loop")));
    }

    private static double counter(
            SimpleMeterRegistry registry,
            String name,
            String tag,
            String value) {
        return registry.get(name).tag(tag, value).counter().count();
    }

    private static DatabaseDurableStateProjectionControlPlane.OperationalSnapshot snapshot() {
        Instant observedAt = Instant.parse("2026-07-17T08:00:00Z");
        return new DatabaseDurableStateProjectionControlPlane.OperationalSnapshot(
                observedAt,
                new DatabaseDurableStateProjectionControlPlane.ControlSnapshot(
                        DurableStateProjectionReconciler.ScanCursor.start(), "", 1,
                        Instant.EPOCH, 1, observedAt.minusSeconds(40)),
                new DatabaseDurableStateProjectionControlPlane.RetentionSnapshot(
                        "", 1, Instant.EPOCH, 1, 10, 5, 5,
                        observedAt.minusSeconds(100), observedAt.minusSeconds(200),
                        observedAt.minusSeconds(80)),
                2, 1, 1, 4, 3, 5, observedAt.minusSeconds(300));
    }
}
