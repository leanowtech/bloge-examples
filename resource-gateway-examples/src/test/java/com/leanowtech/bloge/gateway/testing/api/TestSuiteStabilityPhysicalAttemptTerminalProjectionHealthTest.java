package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestSuiteStabilityPhysicalAttemptTerminalProjectionHealthTest {

    private static final Instant NOW = Instant.parse("2026-07-22T08:00:00Z");

    @Test
    void readyIncludesOnlyAggregateOperationalDetails() {
        var health = health(work(1, 1, 0, NOW.minusSeconds(10)), scheduler(false, false),
                supervisor(0, 0, false)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("runtimeStatus", "READY")
                .containsEntry("dueReadyWork", 1L)
                .containsEntry("expiredLeases", 1L)
                .containsEntry("oldestActionableAgeSeconds", 10L)
                .doesNotContainKeys("tenantId", "attemptId", "leaseOwner", "exception",
                        "providerId", "fingerprint", "payload");
    }

    @Test
    void closedLifecycleFailsReadiness() {
        var health = health(work(0, 0, 0, null), scheduler(true, false),
                supervisor(0, 0, false)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("runtimeStatus", "CLOSED");
    }

    @Test
    void latestUnexpectedPollFailsReadiness() {
        var health = health(work(0, 0, 0, null), scheduler(false, true),
                supervisor(0, 0, false)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("runtimeStatus", "SCHEDULER_FAILED");
    }

    @Test
    void fullyLingeringCoordinatorCapacityFailsReadiness() {
        var health = health(work(0, 0, 0, null), scheduler(false, false),
                supervisor(2, 2, false)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("runtimeStatus", "COORDINATOR_CAPACITY_EXHAUSTED");
    }

    @Test
    void quarantineThresholdFailsReadiness() {
        var health = health(work(0, 0, 1, null), scheduler(false, false),
                supervisor(0, 0, false)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("runtimeStatus", "QUARANTINE_SLO_VIOLATED");
    }

    @Test
    void databaseClockActionableAgeFailsReadiness() {
        var health = health(work(1, 0, 0, NOW.minusSeconds(61)), scheduler(false, false),
                supervisor(0, 0, false)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("runtimeStatus", "ACTIONABLE_AGE_SLO_VIOLATED");
    }

    @Test
    void snapshotOutageFailsClosedWithoutDiagnosticDisclosure() {
        var health = new TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth(
                () -> {
                    throw new IllegalStateException("jdbc:secret and tenant-acme");
                },
                () -> scheduler(false, false),
                () -> supervisor(0, 0, false),
                TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth.Policy.DEFAULT)
                .health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsOnlyKeys("schemaVersion", "runtimeStatus")
                .containsEntry("runtimeStatus", "UNAVAILABLE");
        assertThat(health.getDetails().toString()).doesNotContain("secret", "tenant-acme");
    }

    @Test
    void impossibleFutureActionableTimeFailsClosed() {
        var health = health(work(1, 0, 0, NOW.plusSeconds(1)), scheduler(false, false),
                supervisor(0, 0, false)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsOnlyKeys("schemaVersion", "runtimeStatus")
                .containsEntry("runtimeStatus", "UNAVAILABLE");
    }

    private static TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth health(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Snapshot work,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler.Snapshot scheduler,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Snapshot
                    supervisor) {
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth(
                () -> work, () -> scheduler, () -> supervisor,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth.Policy.DEFAULT);
    }

    private static TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Snapshot work(
            long due, long expired, long quarantined, Instant oldest) {
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Snapshot(
                NOW, due, expired, 3, quarantined, due, expired,
                Optional.ofNullable(oldest));
    }

    private static TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler.Snapshot scheduler(
            boolean closed, boolean failed) {
        long polls = failed ? 1L : 0L;
        long unexpected = failed ? 1L : 0L;
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler.Snapshot(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler.Snapshot
                        .SCHEMA_VERSION,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler.Policy.DEFAULT,
                polls, unexpected, 0, Optional.empty(), Optional.empty(), failed, closed);
    }

    private static TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Snapshot
            supervisor(long active, long lingering, boolean closed) {
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Snapshot(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Snapshot
                        .SCHEMA_VERSION,
                new TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Policy(
                        Duration.ofSeconds(20), 2),
                active, 0, 0, 0, 0, 0, 0, active, lingering, closed);
    }
}
