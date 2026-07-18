package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSuiteStabilityJobRetentionSloMonitorTest {

    private static final Instant NOW = Instant.parse("2026-07-18T08:00:00Z");

    @Test
    void transitionsFromStartupGraceToNeverSucceededUsingDatabaseTime() {
        TestSuiteStabilityJobRepository repository =
                mock(TestSuiteStabilityJobRepository.class);
        TestSuiteStabilityJobRetentionTelemetry telemetry =
                mock(TestSuiteStabilityJobRetentionTelemetry.class);
        when(repository.observeRetention())
                .thenReturn(snapshot(NOW, null, 0, null, 0, null))
                .thenReturn(snapshot(NOW.plusSeconds(181), null, 0, null, 0, null));
        var monitor = new TestSuiteStabilityJobRetentionSloMonitor(
                repository, telemetry, policy());

        monitor.refresh();
        assertThat(monitor.health().getStatus()).isEqualTo(Status.UNKNOWN);
        monitor.refresh();

        assertThat(monitor.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(violations(monitor)).containsExactly("RETENTION_NEVER_SUCCEEDED");
    }

    @Test
    void reportsHealthyOnlyWhenFreshAndBothBacklogsSatisfyPolicy() {
        TestSuiteStabilityJobRepository repository =
                mock(TestSuiteStabilityJobRepository.class);
        TestSuiteStabilityJobRetentionTelemetry telemetry =
                mock(TestSuiteStabilityJobRetentionTelemetry.class);
        TestSuiteStabilityJobRetentionSnapshot healthy =
                snapshot(NOW, NOW.minusSeconds(60), 0, null, 0, null);
        when(repository.observeRetention()).thenReturn(healthy);
        var monitor = new TestSuiteStabilityJobRetentionSloMonitor(
                repository, telemetry, policy());

        monitor.refresh();

        assertThat(monitor.health().getStatus()).isEqualTo(Status.UP);
        assertThat(violations(monitor)).isEmpty();
        verify(telemetry).observeSlo(
                healthy, TestSuiteStabilityJobRetentionSloMonitor.State.HEALTHY,
                Duration.ofSeconds(60), null, null);
    }

    @Test
    void emitsEveryStableFreshnessCountAndAgeViolationWithoutIdentities() {
        TestSuiteStabilityJobRepository repository =
                mock(TestSuiteStabilityJobRepository.class);
        TestSuiteStabilityJobRetentionTelemetry telemetry =
                mock(TestSuiteStabilityJobRetentionTelemetry.class);
        when(repository.observeRetention()).thenReturn(snapshot(
                NOW, NOW.minus(Duration.ofHours(4)),
                2, NOW.minus(Duration.ofHours(2)),
                3, NOW.minus(Duration.ofHours(2))));
        var monitor = new TestSuiteStabilityJobRetentionSloMonitor(
                repository, telemetry, policy());

        monitor.refresh();

        assertThat(monitor.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(violations(monitor)).containsExactly(
                "RETENTION_STALE",
                "JOB_RETENTION_BACKLOG_EXCEEDED",
                "JOB_RETENTION_BACKLOG_STALE",
                "TOMBSTONE_PURGE_BACKLOG_EXCEEDED",
                "TOMBSTONE_PURGE_BACKLOG_STALE");
        assertThat(monitor.health().getDetails())
                .containsEntry("overdueJobs", 2L)
                .containsEntry("expiredTombstones", 3L)
                .doesNotContainKeys("tenantId", "jobId", "clientRequestId", "error");
    }

    @Test
    void failsClosedOnStoreOutageButNotOnPostObservationTelemetryFailure() {
        TestSuiteStabilityJobRepository unavailableRepository =
                mock(TestSuiteStabilityJobRepository.class);
        TestSuiteStabilityJobRetentionTelemetry telemetry =
                mock(TestSuiteStabilityJobRetentionTelemetry.class);
        when(unavailableRepository.observeRetention())
                .thenThrow(new IllegalStateException("database secret"));
        var unavailable = new TestSuiteStabilityJobRetentionSloMonitor(
                unavailableRepository, telemetry, policy());

        unavailable.refresh();

        assertThat(unavailable.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(violations(unavailable)).containsExactly(
                "RETENTION_STORE_UNAVAILABLE");
        assertThat(unavailable.health().getDetails().toString())
                .doesNotContain("database secret");
        verify(telemetry).observeStoreUnavailable();

        TestSuiteStabilityJobRepository healthyRepository =
                mock(TestSuiteStabilityJobRepository.class);
        TestSuiteStabilityJobRetentionTelemetry brokenTelemetry =
                mock(TestSuiteStabilityJobRetentionTelemetry.class);
        when(healthyRepository.observeRetention()).thenReturn(
                snapshot(NOW, NOW.minusSeconds(60), 0, null, 0, null));
        doThrow(new IllegalStateException("metrics unavailable"))
                .when(brokenTelemetry).observeSlo(any(), any(), any(), any(), any());
        var telemetryFailure = new TestSuiteStabilityJobRetentionSloMonitor(
                healthyRepository, brokenTelemetry, policy());

        telemetryFailure.refresh();

        assertThat(telemetryFailure.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void rejectsUnsafeScheduleDurationAndBacklogPoliciesAtAssemblyTime() {
        assertThatThrownBy(() -> new TestSuiteStabilityJobRetentionSloMonitor.Policy(
                Duration.ofMillis(999), Duration.ofMinutes(3), Duration.ofHours(3),
                0, Duration.ofHours(1), 0, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("observationInterval");
        assertThatThrownBy(() -> new TestSuiteStabilityJobRetentionSloMonitor.Policy(
                Duration.ofSeconds(30), Duration.ofMinutes(3), Duration.ofHours(3),
                -1, Duration.ofHours(1), 0, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    private static TestSuiteStabilityJobRetentionSloMonitor.Policy policy() {
        return new TestSuiteStabilityJobRetentionSloMonitor.Policy(
                Duration.ofSeconds(30), Duration.ofMinutes(3), Duration.ofHours(3),
                0, Duration.ofHours(1), 0, Duration.ofHours(1));
    }

    private static TestSuiteStabilityJobRetentionSnapshot snapshot(
            Instant observedAt,
            Instant lastSuccessAt,
            long overdueJobs,
            Instant oldestOverdueJob,
            long expiredTombstones,
            Instant oldestExpiredTombstone) {
        return new TestSuiteStabilityJobRetentionSnapshot(
                "", 1, Instant.EPOCH, 2, 4, 3,
                5, 3, overdueJobs, expiredTombstones,
                oldestOverdueJob, oldestExpiredTombstone,
                lastSuccessAt, observedAt);
    }

    @SuppressWarnings("unchecked")
    private static List<String> violations(
            TestSuiteStabilityJobRetentionSloMonitor monitor) {
        return (List<String>) monitor.health().getDetails().get("violations");
    }
}
