package com.leanowtech.bloge.gateway.testing.api;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestSuiteStabilityJobTelemetryTest {

    @Test
    void exportsOnlyClosedEnvironmentStatusAndOutcomeDimensions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TestSuiteStabilityJobTelemetry telemetry =
                new TestSuiteStabilityJobTelemetry(registry);
        TestSuiteStabilityQueueSnapshot snapshot = new TestSuiteStabilityQueueSnapshot(
                Instant.parse("2026-07-18T10:00:00Z"),
                Map.of(TestSuiteStabilityJobRecord.Status.QUEUED, 3L,
                        TestSuiteStabilityJobRecord.Status.RUNNING, 2L),
                Instant.parse("2026-07-18T09:59:18Z"), 1, 2);

        telemetry.observe("test", snapshot,
                TestSuiteStabilityJobSloMonitor.State.SLO_VIOLATED,
                Duration.ofSeconds(42));
        telemetry.recordPoll("test", TestSuiteStabilityJobWorkResult.noWork());
        telemetry.recordUnexpectedPoll("staging");
        telemetry.workerStarted();
        telemetry.activePolls(2);
        telemetry.workerStopped(1);

        assertThat(gauge(registry, "resource.gateway.test.stability.jobs.queue.records",
                "environment", "test", "status", "queued")).isEqualTo(3.0);
        assertThat(gauge(registry, "resource.gateway.test.stability.jobs.queue.records",
                "environment", "test", "status", "running")).isEqualTo(2.0);
        assertThat(gauge(registry, "resource.gateway.test.stability.jobs.queue.oldest.age",
                "environment", "test")).isEqualTo(42.0);
        assertThat(gauge(registry, "resource.gateway.test.stability.jobs.queue.expired.leases",
                "environment", "test")).isEqualTo(1.0);
        assertThat(gauge(registry, "resource.gateway.test.stability.jobs.queue.queued.tenants",
                "environment", "test")).isEqualTo(2.0);
        assertThat(gauge(registry, "resource.gateway.test.stability.jobs.queue.health",
                "environment", "test")).isEqualTo(-1.0);
        assertThat(counter(registry, "resource.gateway.test.stability.jobs.worker.polls",
                "environment", "test", "outcome", "no_work")).isEqualTo(1.0);
        assertThat(counter(registry, "resource.gateway.test.stability.jobs.worker.unexpected",
                "environment", "staging")).isEqualTo(1.0);
        assertThat(registry.get("resource.gateway.test.stability.jobs.worker.configured")
                .gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("resource.gateway.test.stability.jobs.worker.active")
                .gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("resource.gateway.test.stability.jobs.worker.closed")
                .gauge().value()).isEqualTo(1.0);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag ->
                        assertThat(tag.getKey()).isIn("environment", "status", "outcome")));
    }

    @Test
    void storeOutageMarksEveryPriorQueueGaugeUnknownWithoutExceptionText() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TestSuiteStabilityJobTelemetry telemetry =
                new TestSuiteStabilityJobTelemetry(registry);
        TestSuiteStabilityQueueSnapshot snapshot = new TestSuiteStabilityQueueSnapshot(
                Instant.EPOCH,
                Map.of(TestSuiteStabilityJobRecord.Status.QUEUED, 1L),
                Instant.EPOCH, 0, 1);
        telemetry.observe("staging", snapshot,
                TestSuiteStabilityJobSloMonitor.State.HEALTHY, Duration.ZERO);

        telemetry.observeStoreUnavailable("staging");

        assertThat(gauge(registry, "resource.gateway.test.stability.jobs.queue.records",
                "environment", "staging", "status", "queued")).isEqualTo(-1.0);
        assertThat(gauge(registry, "resource.gateway.test.stability.jobs.queue.oldest.age",
                "environment", "staging")).isEqualTo(-1.0);
        assertThat(gauge(registry, "resource.gateway.test.stability.jobs.queue.health",
                "environment", "staging")).isEqualTo(-2.0);
    }

    private static double gauge(
            SimpleMeterRegistry registry, String name, String... tags) {
        return registry.get(name).tags(tags).gauge().value();
    }

    private static double counter(
            SimpleMeterRegistry registry, String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }
}
