package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSuiteStabilityJobSloMonitorTest {

    private static final Instant NOW = Instant.parse("2026-07-18T10:00:00Z");

    @Test
    void businessFailureTerminalsDoNotMakeAnOperationallyHealthyQueueUnready() {
        TestSuiteStabilityJobRepository repository = mock(TestSuiteStabilityJobRepository.class);
        TestSuiteStabilityJobTelemetry telemetry = mock(TestSuiteStabilityJobTelemetry.class);
        TestSuiteStabilityQueueSnapshot snapshot = snapshot(
                Map.of(TestSuiteStabilityJobRecord.Status.FAILED, 9_999L), null, 0, 0);
        when(repository.observe("test")).thenReturn(snapshot);
        var monitor = monitor(repository, telemetry, Set.of("test"));

        monitor.refresh();

        assertThat(monitor.health().getStatus()).isEqualTo(Status.UP);
        assertThat(environment(monitor, "test"))
                .containsEntry("state", "HEALTHY")
                .containsEntry("violations", List.of());
        verify(telemetry).observe("test", snapshot,
                TestSuiteStabilityJobSloMonitor.State.HEALTHY, null);
    }

    @Test
    void reportsDepthAgeAndExpiredLeaseViolationsFromDatabaseTime() {
        TestSuiteStabilityJobRepository repository = mock(TestSuiteStabilityJobRepository.class);
        TestSuiteStabilityJobTelemetry telemetry = mock(TestSuiteStabilityJobTelemetry.class);
        TestSuiteStabilityQueueSnapshot snapshot = snapshot(
                Map.of(TestSuiteStabilityJobRecord.Status.QUEUED, 11L),
                NOW.minusSeconds(301), 2, 3);
        when(repository.observe("staging")).thenReturn(snapshot);
        var monitor = monitor(repository, telemetry, Set.of("staging"));

        monitor.refresh();

        assertThat(monitor.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(environment(monitor, "staging").get("violations"))
                .isEqualTo(List.of("QUEUE_DEPTH_EXCEEDED", "QUEUE_BACKLOG_STALE",
                        "EXPIRED_LIVE_LEASE_BACKLOG"));
        assertThat(environment(monitor, "staging"))
                .containsEntry("oldestQueuedAgeSeconds", 301L)
                .containsEntry("expiredLiveLeases", 2L)
                .containsEntry("distinctQueuedTenants", 3L)
                .doesNotContainKeys("tenantId", "jobId", "failureCode");
    }

    @Test
    void oneEnvironmentStoreOutageFailsAggregateHealthClosedWithoutLeakingCause() {
        TestSuiteStabilityJobRepository repository = mock(TestSuiteStabilityJobRepository.class);
        TestSuiteStabilityJobTelemetry telemetry = mock(TestSuiteStabilityJobTelemetry.class);
        when(repository.observe("test")).thenReturn(snapshot(Map.of(), null, 0, 0));
        when(repository.observe("staging"))
                .thenThrow(new IllegalStateException("database password secret"));
        var monitor = monitor(repository, telemetry, Set.of("test", "staging"));

        monitor.refresh();

        assertThat(monitor.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(environment(monitor, "staging"))
                .containsEntry("state", "STORE_UNAVAILABLE")
                .containsEntry("violations", List.of("QUEUE_STORE_UNAVAILABLE"));
        assertThat(monitor.health().getDetails().toString())
                .doesNotContain("password")
                .doesNotContain("secret");
        verify(telemetry).observeStoreUnavailable("staging");
    }

    @Test
    void telemetryFailureDoesNotChangeACompletedDatabaseAssessment() {
        TestSuiteStabilityJobRepository repository = mock(TestSuiteStabilityJobRepository.class);
        TestSuiteStabilityJobTelemetry telemetry = mock(TestSuiteStabilityJobTelemetry.class);
        TestSuiteStabilityQueueSnapshot snapshot = snapshot(Map.of(), null, 0, 0);
        when(repository.observe("test")).thenReturn(snapshot);
        doThrow(new IllegalStateException("metrics unavailable"))
                .when(telemetry).observe(eq("test"), eq(snapshot), any(), any());
        var monitor = monitor(repository, telemetry, Set.of("test"));

        monitor.refresh();

        assertThat(monitor.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void rejectsProductionEmptyAndUnboundedSloCoordinates() {
        TestSuiteStabilityJobRepository repository = mock(TestSuiteStabilityJobRepository.class);
        TestSuiteStabilityJobTelemetry telemetry = TestSuiteStabilityJobTelemetry.noop();
        assertThatThrownBy(() -> new TestSuiteStabilityJobSloMonitor(
                repository, telemetry, Set.of("production"), policy()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("test and/or staging");
        assertThatThrownBy(() -> new TestSuiteStabilityJobSloMonitor(
                repository, telemetry, Set.of(), policy()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestSuiteStabilityJobSloMonitor.Policy(
                Duration.ofMillis(999), 10, Duration.ofMinutes(5), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("observationInterval");
        assertThatThrownBy(() -> new TestSuiteStabilityJobSloMonitor.Policy(
                Duration.ofSeconds(30), -1, Duration.ofMinutes(5), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 100000");
        assertThatThrownBy(() -> new TestSuiteStabilityQueueSnapshot(
                NOW, Map.of(TestSuiteStabilityJobRecord.Status.QUEUED, 1L),
                NOW.plusSeconds(1), 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid suite-stability queue snapshot");
    }

    private static TestSuiteStabilityJobSloMonitor monitor(
            TestSuiteStabilityJobRepository repository,
            TestSuiteStabilityJobTelemetry telemetry,
            Set<String> environments) {
        return new TestSuiteStabilityJobSloMonitor(
                repository, telemetry, environments, policy());
    }

    private static TestSuiteStabilityJobSloMonitor.Policy policy() {
        return new TestSuiteStabilityJobSloMonitor.Policy(
                Duration.ofSeconds(30), 10, Duration.ofMinutes(5), 0);
    }

    private static TestSuiteStabilityQueueSnapshot snapshot(
            Map<TestSuiteStabilityJobRecord.Status, Long> totals,
            Instant oldestQueuedAt,
            long expiredLiveLeases,
            long tenants) {
        return new TestSuiteStabilityQueueSnapshot(
                NOW, totals, oldestQueuedAt, expiredLiveLeases, tenants);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> environment(
            TestSuiteStabilityJobSloMonitor monitor, String environment) {
        Map<String, Object> environments = (Map<String, Object>)
                monitor.health().getDetails().get("environments");
        return (Map<String, Object>) environments.get(environment);
    }
}
