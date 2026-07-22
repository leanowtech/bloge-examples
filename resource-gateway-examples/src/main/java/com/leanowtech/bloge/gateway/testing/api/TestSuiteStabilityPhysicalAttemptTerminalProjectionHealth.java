package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Aggregate-only readiness for physical-attempt terminal-projection execution.
 *
 * <p>The indicator reads database-clock backlog, scheduler lifecycle, and fixed local coordinator
 * capacity. It performs no provider call and exposes no tenant, attempt, lease, owner, command,
 * runtime binding, fingerprint, payload, exception, or storage diagnostic.</p>
 */
public final class TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth
        implements HealthIndicator {

    private final Supplier<TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Snapshot>
            workSnapshot;
    private final Supplier<TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler.Snapshot>
            schedulerSnapshot;
    private final Supplier<
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Snapshot>
            supervisorSnapshot;
    private final Policy policy;

    /**
     * Creates readiness over the active database and local runtime owners.
     *
     * @param works database-authoritative terminal-projection work journal
     * @param scheduler process-local polling owner
     * @param supervisor fixed-capacity coordinator-call owner
     * @param policy actionable-age and quarantine readiness thresholds
     */
    public TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal works,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler scheduler,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor supervisor,
            Policy policy) {
        this(Objects.requireNonNull(works, "works")::snapshot,
                Objects.requireNonNull(scheduler, "scheduler")::snapshot,
                Objects.requireNonNull(supervisor, "supervisor")::snapshot,
                policy);
    }

    TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth(
            Supplier<TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Snapshot>
                    workSnapshot,
            Supplier<TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler.Snapshot>
                    schedulerSnapshot,
            Supplier<TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Snapshot>
                    supervisorSnapshot,
            Policy policy) {
        this.workSnapshot = Objects.requireNonNull(workSnapshot, "workSnapshot");
        this.schedulerSnapshot = Objects.requireNonNull(
                schedulerSnapshot, "schedulerSnapshot");
        this.supervisorSnapshot = Objects.requireNonNull(
                supervisorSnapshot, "supervisorSnapshot");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Returns UP only while local lifecycle, capacity, and durable backlog remain serviceable.
     *
     * @return payload-free Actuator health
     */
    @Override
    public Health health() {
        try {
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Snapshot work =
                    Objects.requireNonNull(workSnapshot.get(), "work snapshot");
            TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler.Snapshot scheduler =
                    Objects.requireNonNull(schedulerSnapshot.get(), "scheduler snapshot");
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Snapshot supervisor =
                    Objects.requireNonNull(supervisorSnapshot.get(), "supervisor snapshot");
            RuntimeStatus status = classify(work, scheduler, supervisor, policy);
            return (status == RuntimeStatus.READY ? Health.up() : Health.down())
                    .withDetails(details(work, scheduler, supervisor, status)).build();
        } catch (RuntimeException unavailable) {
            return Health.down()
                    .withDetail("schemaVersion", SnapshotSchema.VERSION)
                    .withDetail("runtimeStatus", RuntimeStatus.UNAVAILABLE.name())
                    .build();
        }
    }

    private static RuntimeStatus classify(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Snapshot work,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler.Snapshot scheduler,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Snapshot supervisor,
            Policy policy) {
        if (scheduler.closed() || supervisor.closed()) {
            return RuntimeStatus.CLOSED;
        }
        if (scheduler.lastPollFailed()) {
            return RuntimeStatus.SCHEDULER_FAILED;
        }
        if (supervisor.activeCalls() == supervisor.policy().maximumConcurrentCalls()
                && supervisor.lingeringCalls() == supervisor.activeCalls()) {
            return RuntimeStatus.COORDINATOR_CAPACITY_EXHAUSTED;
        }
        if (work.quarantined() > policy.maximumQuarantinedWork()) {
            return RuntimeStatus.QUARANTINE_SLO_VIOLATED;
        }
        if (actionableAge(work).compareTo(policy.maximumActionableAge()) > 0) {
            return RuntimeStatus.ACTIONABLE_AGE_SLO_VIOLATED;
        }
        return RuntimeStatus.READY;
    }

    private static Duration actionableAge(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Snapshot work) {
        Optional<Instant> oldest = work.oldestActionableAt();
        if (oldest.isEmpty()) {
            return Duration.ZERO;
        }
        Duration age = Duration.between(oldest.orElseThrow(), work.observedAt());
        if (age.isNegative()) {
            throw new IllegalArgumentException(
                    "Physical-attempt terminal projection work snapshot is invalid");
        }
        return age;
    }

    private static Map<String, Object> details(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Snapshot work,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler.Snapshot scheduler,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Snapshot supervisor,
            RuntimeStatus status) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("schemaVersion", SnapshotSchema.VERSION);
        details.put("runtimeStatus", status.name());
        details.put("workObservedAt", work.observedAt().toString());
        details.put("readyWork", work.ready());
        details.put("leasedWork", work.leased());
        details.put("completedWork", work.completed());
        details.put("quarantinedWork", work.quarantined());
        details.put("dueReadyWork", work.dueReady());
        details.put("expiredLeases", work.expiredLeases());
        details.put("oldestActionableAgeSeconds", actionableAge(work).toSeconds());
        details.putAll(scheduler.details());
        details.put("coordinatorCallCapacity",
                supervisor.policy().maximumConcurrentCalls());
        details.put("activeCoordinatorCalls", supervisor.activeCalls());
        details.put("lingeringCoordinatorCalls", supervisor.lingeringCalls());
        details.put("timedOutCoordinatorCalls", supervisor.timedOutCalls());
        details.put("saturatedCoordinatorCalls", supervisor.saturatedCalls());
        details.put("failedCoordinatorCalls", supervisor.failedCalls());
        return Map.copyOf(details);
    }

    /** Bounded readiness classification without work or provider identity. */
    public enum RuntimeStatus {
        /** Local scheduling and durable backlog are serviceable. */
        READY,
        /** Scheduler or call supervisor has closed. */
        CLOSED,
        /** The latest local poll threw or returned no bounded result. */
        SCHEDULER_FAILED,
        /** Every coordinator slot is occupied by an interruption-ignoring call. */
        COORDINATOR_CAPACITY_EXHAUSTED,
        /** Durable quarantine exceeds the explicitly accepted threshold. */
        QUARANTINE_SLO_VIOLATED,
        /** Due or expired durable work has exceeded its database-clock age threshold. */
        ACTIONABLE_AGE_SLO_VIOLATED,
        /** One or more aggregate snapshots could not be read safely. */
        UNAVAILABLE
    }

    /**
     * Readiness thresholds for durable terminal-projection work.
     *
     * @param maximumActionableAge maximum due or expired backlog age from 1 s through one day
     * @param maximumQuarantinedWork accepted quarantined rows from zero through one million
     */
    public record Policy(Duration maximumActionableAge, long maximumQuarantinedWork) {

        /** Default one-minute actionable SLO with no accepted quarantine. */
        public static final Policy DEFAULT = new Policy(Duration.ofMinutes(1), 0L);

        /** Enforces finite millisecond-exact aggregate thresholds. */
        public Policy {
            maximumActionableAge = Objects.requireNonNull(
                    maximumActionableAge, "maximumActionableAge");
            if (maximumActionableAge.compareTo(Duration.ofSeconds(1)) < 0
                    || maximumActionableAge.compareTo(Duration.ofDays(1)) > 0
                    || !maximumActionableAge.equals(
                    Duration.ofMillis(maximumActionableAge.toMillis()))
                    || maximumQuarantinedWork < 0 || maximumQuarantinedWork > 1_000_000L) {
                throw new IllegalArgumentException(
                        "Physical-attempt terminal projection health policy is invalid");
            }
        }
    }

    private static final class SnapshotSchema {
        private static final String VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptTerminalProjectionHealth.v1";

        private SnapshotSchema() {
        }
    }
}
