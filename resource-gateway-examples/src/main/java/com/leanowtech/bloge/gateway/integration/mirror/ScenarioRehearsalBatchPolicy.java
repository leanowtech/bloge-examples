package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.util.Objects;

/**
 * Cross-replica queue and execution bounds for durable Scenario rehearsal batches.
 *
 * <p>Every replica must use the same generation and effective values. The database repository
 * persists the policy fingerprint per region and environment and fails closed when a rolling
 * deployment presents a different policy under the same generation.</p>
 *
 * @param generation monotonically increasing policy generation
 * @param failureMode server-owned non-passing item behavior
 * @param priority server-owned base scheduling priority
 * @param maximumItemAttempts bounded infrastructure attempts for each item
 * @param maximumQueued maximum active batches per regional environment partition
 * @param maximumQueuedPerTenant maximum active batches per tenant and regional environment
 * @param maximumRunning maximum live item leases per regional environment partition
 * @param maximumRunningPerTenant maximum live item leases per tenant and regional environment
 * @param maximumPlanTimeout largest compiled-plan total timeout admitted to a batch
 * @param maximumDeadlineHorizon furthest accepted batch deadline
 * @param leaseReserve time reserved after a plan's declared timeout for evidence publication
 * @param retryBackoff deterministic delay between infrastructure attempts
 * @param priorityAgingInterval wait required to gain one scheduling priority level
 * @param terminalRetention detailed terminal job retention
 */
public record ScenarioRehearsalBatchPolicy(
        long generation,
        FailureMode failureMode,
        Priority priority,
        int maximumItemAttempts,
        int maximumQueued,
        int maximumQueuedPerTenant,
        int maximumRunning,
        int maximumRunningPerTenant,
        Duration maximumPlanTimeout,
        Duration maximumDeadlineHorizon,
        Duration leaseReserve,
        Duration retryBackoff,
        Duration priorityAgingInterval,
        Duration terminalRetention
) {
    /** Server-owned behavior after one evidence-backed non-passing item. */
    public enum FailureMode {
        FAIL_FAST,
        COLLECT_ALL
    }

    /** Server-owned base priority; queue aging still guarantees eventual promotion. */
    public enum Priority {
        LOW(0),
        NORMAL(1),
        HIGH(2);

        private final int weight;

        Priority(int weight) {
            this.weight = weight;
        }

        /** @return stable queue weight before wait-time aging */
        public int weight() {
            return weight;
        }
    }

    /** Conservative local defaults suitable for the isolated test/staging runtime. */
    public static ScenarioRehearsalBatchPolicy defaults() {
        return new ScenarioRehearsalBatchPolicy(
                1,
                FailureMode.COLLECT_ALL,
                Priority.NORMAL,
                3,
                1_000,
                100,
                16,
                4,
                Duration.ofHours(2),
                Duration.ofDays(7),
                Duration.ofMinutes(1),
                Duration.ofSeconds(5),
                Duration.ofMinutes(10),
                Duration.ofDays(30));
    }

    /** Validates queue cardinalities and bounded time policy. */
    public ScenarioRehearsalBatchPolicy {
        failureMode = Objects.requireNonNull(
                failureMode, "failureMode");
        priority = Objects.requireNonNull(priority, "priority");
        if (generation < 1
                || maximumItemAttempts < 1
                || maximumItemAttempts > 5
                || maximumQueued < 1
                || maximumQueued > 1_000_000
                || maximumQueuedPerTenant < 1
                || maximumQueuedPerTenant > maximumQueued
                || maximumRunning < 1
                || maximumRunning > maximumQueued
                || maximumRunningPerTenant < 1
                || maximumRunningPerTenant > maximumRunning) {
            throw new IllegalArgumentException(
                    "Scenario batch queue cardinalities are invalid");
        }
        maximumPlanTimeout = bounded(
                maximumPlanTimeout, "maximumPlanTimeout",
                Duration.ofSeconds(1), Duration.ofDays(1));
        maximumDeadlineHorizon = bounded(
                maximumDeadlineHorizon, "maximumDeadlineHorizon",
                maximumPlanTimeout, Duration.ofDays(90));
        leaseReserve = bounded(
                leaseReserve, "leaseReserve",
                Duration.ofSeconds(1), Duration.ofHours(1));
        retryBackoff = bounded(
                retryBackoff, "retryBackoff",
                Duration.ofMillis(100), Duration.ofHours(1));
        priorityAgingInterval = bounded(
                priorityAgingInterval, "priorityAgingInterval",
                Duration.ofSeconds(1), Duration.ofDays(1));
        terminalRetention = bounded(
                terminalRetention, "terminalRetention",
                Duration.ofDays(1), Duration.ofDays(3650));
    }

    private static Duration bounded(
            Duration value,
            String field,
            Duration minimum,
            Duration maximum) {
        Duration result = Objects.requireNonNull(value, field);
        if (result.compareTo(minimum) < 0
                || result.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    field + " is outside the supported Scenario batch bound");
        }
        return result;
    }
}
