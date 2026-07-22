package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSuiteStabilityPhysicalAttemptObservationReconciliationHealthTest {

    private static final Instant NOW = Instant.parse("2026-07-22T08:00:00Z");

    @Test
    void readyIncludesOnlyAggregateOperationalDetails() {
        var health = health(work(1, 1, 0, 0, NOW.minusSeconds(10)),
                scheduler(false, false), supervisor(0, 0, false)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("runtimeStatus", "READY")
                .containsEntry("dueTargets", 1L)
                .containsEntry("expiredLeases", 1L)
                .containsEntry("undiscoveredSources", 0L)
                .containsEntry("oldestActionableAgeSeconds", 10L)
                .doesNotContainKeys("tenantId", "attemptId", "leaseOwner", "workerId",
                        "exception", "providerId", "deploymentId", "fingerprint", "payload");
    }

    @Test
    void closedLifecycleFailsReadiness() {
        var health = health(work(0, 0, 0, 0, null), scheduler(true, false),
                supervisor(0, 0, false)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("runtimeStatus", "CLOSED");
    }

    @Test
    void latestUnexpectedPollFailsReadiness() {
        var health = health(work(0, 0, 0, 0, null), scheduler(false, true),
                supervisor(0, 0, false)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("runtimeStatus", "SCHEDULER_FAILED");
    }

    @Test
    void fullyLingeringProviderCapacityFailsReadiness() {
        var health = health(work(0, 0, 0, 0, null), scheduler(false, false),
                supervisor(2, 2, false)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("runtimeStatus", "PROVIDER_CAPACITY_EXHAUSTED");
    }

    @Test
    void quarantineThresholdFailsReadiness() {
        var health = health(work(0, 0, 1, 0, null), scheduler(false, false),
                supervisor(0, 0, false)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("runtimeStatus", "QUARANTINE_SLO_VIOLATED");
    }

    @Test
    void undiscoveredSourceThresholdFailsReadiness() {
        var health = health(work(0, 0, 0, 1, null), scheduler(false, false),
                supervisor(0, 0, false)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("runtimeStatus", "UNDISCOVERED_SOURCE_SLO_VIOLATED");
    }

    @Test
    void databaseClockActionableAgeFailsReadiness() {
        var health = health(work(1, 0, 0, 0, NOW.minusSeconds(61)),
                scheduler(false, false), supervisor(0, 0, false)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("runtimeStatus", "ACTIONABLE_AGE_SLO_VIOLATED");
    }

    @Test
    void snapshotOutageFailsClosedWithoutDiagnosticDisclosure() {
        var health = new TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth(
                () -> {
                    throw new IllegalStateException("jdbc:secret and tenant-acme");
                },
                () -> scheduler(false, false),
                () -> supervisor(0, 0, false),
                TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth.Policy.DEFAULT)
                .health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsOnlyKeys("schemaVersion", "runtimeStatus")
                .containsEntry("runtimeStatus", "UNAVAILABLE");
        assertThat(health.getDetails().toString()).doesNotContain("secret", "tenant-acme");
    }

    @Test
    void impossibleFutureActionableTimeFailsClosed() {
        var invalid = mock(
                TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Snapshot.class);
        when(invalid.databaseTime()).thenReturn(NOW);
        when(invalid.oldestDueAt()).thenReturn(Optional.of(NOW.plusSeconds(1)));
        when(invalid.quarantined()).thenReturn(0L);
        when(invalid.undiscoveredSources()).thenReturn(0L);
        var health = health(invalid,
                scheduler(false, false), supervisor(0, 0, false)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsOnlyKeys("schemaVersion", "runtimeStatus")
                .containsEntry("runtimeStatus", "UNAVAILABLE");
    }

    private static TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth health(
            TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Snapshot work,
            TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler.Snapshot scheduler,
            TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Snapshot supervisor) {
        return new TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth(
                () -> work, () -> scheduler, () -> supervisor,
                TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth.Policy.DEFAULT);
    }

    private static TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Snapshot work(
            long due, long expired, long quarantined, long undiscovered, Instant oldest) {
        return new TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Snapshot(
                NOW, due, expired, 3, quarantined, due, expired, undiscovered,
                Optional.ofNullable(oldest));
    }

    private static TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler.Snapshot
            scheduler(boolean closed, boolean failed) {
        long polls = failed ? 1L : 0L;
        long unexpected = failed ? 1L : 0L;
        return new TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler.Snapshot(
                TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler.Snapshot
                        .SCHEMA_VERSION,
                TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler.Policy.DEFAULT,
                polls, unexpected, 0, Optional.empty(), failed, closed);
    }

    private static TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Snapshot supervisor(
            long active, long lingering, boolean closed) {
        return new TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Snapshot(
                TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Snapshot
                        .SCHEMA_VERSION,
                new TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Policy(
                        Duration.ofSeconds(5), Duration.ofSeconds(20), 2),
                active, 0, 0, 0, 0, 0, 0, active, lingering, closed);
    }
}
