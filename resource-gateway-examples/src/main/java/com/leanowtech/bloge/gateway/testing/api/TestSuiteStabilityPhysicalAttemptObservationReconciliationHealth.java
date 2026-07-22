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
 * Aggregate readiness for autonomous physical-attempt observation reconciliation.
 *
 * <p>The indicator performs no provider call. It combines database-clock discovery/backlog state,
 * local scheduler lifecycle, and fixed observation-call capacity without exposing target, tenant,
 * worker, provider, lease, fingerprint, exception, or business payload data.</p>
 */
public final class TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth
        implements HealthIndicator {

    private final Supplier<TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
            .Snapshot> workSnapshot;
    private final Supplier<TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler
            .Snapshot> schedulerSnapshot;
    private final Supplier<TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Snapshot>
            supervisorSnapshot;
    private final Policy policy;

    /**
     * Creates readiness over the active database and local runtime owners.
     *
     * @param work database-authoritative reconciliation journal
     * @param scheduler process-local polling owner
     * @param supervisor fixed-capacity provider-call owner
     * @param policy actionable-age, discovery, and quarantine thresholds
     */
    public TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth(
            TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal work,
            TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler scheduler,
            TestSuiteStabilityPhysicalAttemptObservationCallSupervisor supervisor,
            Policy policy) {
        this(Objects.requireNonNull(work, "work")::snapshot,
                Objects.requireNonNull(scheduler, "scheduler")::snapshot,
                Objects.requireNonNull(supervisor, "supervisor")::snapshot,
                policy);
    }

    TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth(
            Supplier<TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Snapshot>
                    workSnapshot,
            Supplier<TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler.Snapshot>
                    schedulerSnapshot,
            Supplier<TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Snapshot>
                    supervisorSnapshot,
            Policy policy) {
        this.workSnapshot = Objects.requireNonNull(workSnapshot, "workSnapshot");
        this.schedulerSnapshot = Objects.requireNonNull(schedulerSnapshot, "schedulerSnapshot");
        this.supervisorSnapshot = Objects.requireNonNull(supervisorSnapshot, "supervisorSnapshot");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Returns UP only while lifecycle, capacity, discovery, and durable backlog are serviceable.
     *
     * @return payload-free Actuator health
     */
    @Override
    public Health health() {
        try {
            TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Snapshot work =
                    Objects.requireNonNull(workSnapshot.get(), "work snapshot");
            TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler.Snapshot scheduler =
                    Objects.requireNonNull(schedulerSnapshot.get(), "scheduler snapshot");
            TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Snapshot supervisor =
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
            TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Snapshot work,
            TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler.Snapshot scheduler,
            TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Snapshot supervisor,
            Policy policy) {
        if (scheduler.closed() || supervisor.closed()) {
            return RuntimeStatus.CLOSED;
        }
        if (scheduler.lastPollFailed()) {
            return RuntimeStatus.SCHEDULER_FAILED;
        }
        if (supervisor.activeCalls() == supervisor.policy().maximumConcurrentCalls()
                && supervisor.lingeringCalls() == supervisor.activeCalls()) {
            return RuntimeStatus.PROVIDER_CAPACITY_EXHAUSTED;
        }
        if (work.quarantined() > policy.maximumQuarantinedTargets()) {
            return RuntimeStatus.QUARANTINE_SLO_VIOLATED;
        }
        if (work.undiscoveredSources() > policy.maximumUndiscoveredSources()) {
            return RuntimeStatus.UNDISCOVERED_SOURCE_SLO_VIOLATED;
        }
        if (actionableAge(work).compareTo(policy.maximumActionableAge()) > 0) {
            return RuntimeStatus.ACTIONABLE_AGE_SLO_VIOLATED;
        }
        return RuntimeStatus.READY;
    }

    private static Duration actionableAge(
            TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Snapshot work) {
        Optional<Instant> oldest = work.oldestDueAt();
        if (oldest.isEmpty()) {
            return Duration.ZERO;
        }
        Duration age = Duration.between(oldest.orElseThrow(), work.databaseTime());
        if (age.isNegative()) {
            throw new IllegalArgumentException(
                    "Physical-attempt observation reconciliation snapshot is invalid");
        }
        return age;
    }

    private static Map<String, Object> details(
            TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Snapshot work,
            TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler.Snapshot scheduler,
            TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Snapshot supervisor,
            RuntimeStatus status) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("schemaVersion", SnapshotSchema.VERSION);
        details.put("runtimeStatus", status.name());
        details.put("workObservedAt", work.databaseTime().toString());
        details.put("readyTargets", work.ready());
        details.put("leasedTargets", work.leased());
        details.put("terminalTargets", work.terminal());
        details.put("quarantinedTargets", work.quarantined());
        details.put("dueTargets", work.due());
        details.put("expiredLeases", work.expiredLeases());
        details.put("undiscoveredSources", work.undiscoveredSources());
        details.put("oldestActionableAgeSeconds", actionableAge(work).toSeconds());
        details.putAll(scheduler.details());
        details.put("providerCallCapacity", supervisor.policy().maximumConcurrentCalls());
        details.put("activeProviderCalls", supervisor.activeCalls());
        details.put("lingeringProviderCalls", supervisor.lingeringCalls());
        details.put("timedOutProviderCalls", supervisor.timedOutCalls());
        details.put("saturatedProviderCalls", supervisor.saturatedCalls());
        details.put("failedProviderCalls", supervisor.failedCalls());
        return Map.copyOf(details);
    }

    /** Bounded readiness classification without target or provider identity. */
    public enum RuntimeStatus {
        /** Local scheduling and durable discovery/backlog are serviceable. */
        READY,
        /** Scheduler or call supervisor has closed. */
        CLOSED,
        /** The latest local poll threw or returned no bounded result. */
        SCHEDULER_FAILED,
        /** Every provider-call slot is occupied by an interruption-ignoring adapter. */
        PROVIDER_CAPACITY_EXHAUSTED,
        /** Durable quarantine exceeds the explicitly accepted threshold. */
        QUARANTINE_SLO_VIOLATED,
        /** Retained starts awaiting target materialization exceed the accepted threshold. */
        UNDISCOVERED_SOURCE_SLO_VIOLATED,
        /** Due or expired durable work exceeded its database-clock age threshold. */
        ACTIONABLE_AGE_SLO_VIOLATED,
        /** One or more aggregate snapshots could not be read safely. */
        UNAVAILABLE
    }

    /**
     * Readiness thresholds for durable reconciliation work.
     *
     * @param maximumActionableAge maximum due or expired age from 1 s through one day
     * @param maximumQuarantinedTargets accepted quarantined targets from zero through one million
     * @param maximumUndiscoveredSources accepted retained starts awaiting target materialization
     */
    public record Policy(
            Duration maximumActionableAge,
            long maximumQuarantinedTargets,
            long maximumUndiscoveredSources) {

        /** Default one-minute actionable SLO with no accepted quarantine or discovery lag. */
        public static final Policy DEFAULT = new Policy(Duration.ofMinutes(1), 0L, 0L);

        /** Enforces finite millisecond-exact aggregate thresholds. */
        public Policy {
            maximumActionableAge = Objects.requireNonNull(
                    maximumActionableAge, "maximumActionableAge");
            if (maximumActionableAge.compareTo(Duration.ofSeconds(1)) < 0
                    || maximumActionableAge.compareTo(Duration.ofDays(1)) > 0
                    || !maximumActionableAge.equals(
                    Duration.ofMillis(maximumActionableAge.toMillis()))
                    || maximumQuarantinedTargets < 0
                    || maximumQuarantinedTargets > 1_000_000L
                    || maximumUndiscoveredSources < 0
                    || maximumUndiscoveredSources > 1_000_000L) {
                throw new IllegalArgumentException(
                        "Physical-attempt observation reconciliation health policy is invalid");
            }
        }
    }

    private static final class SnapshotSchema {
        private static final String VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptObservationReconciliationHealth.v1";

        private SnapshotSchema() {
        }
    }
}
