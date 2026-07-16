package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.core.runtime.execution.ExecutionStatus;
import com.leanowtech.bloge.core.runtime.work.WorkItemStatus;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeSloControlPlane;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded-cardinality Micrometer projection of global test-runtime SLO observations.
 *
 * <p>Every tag value comes from a closed enum. Tenant, run, suite, owner, work-item, token,
 * exception, and payload values are structurally excluded from metric identity.</p>
 */
public final class TestRuntimeSloTelemetry {
    private static final String PREFIX = "resource.gateway.test.runtime.";

    private final boolean enabled;
    private final Map<TestRunEvidence.Status, AtomicLong> executionOutcomes;
    private final Map<TestSuiteRunEvidence.Status, AtomicLong> suiteOutcomes;
    private final Map<DurableTestExecutionCheckpoint.Status, AtomicLong> durableStates;
    private final Map<ExecutionStatus, AtomicLong> engineStates;
    private final Map<WorkItemStatus, AtomicLong> workStates;
    private final Map<Queue, AtomicLong> queueDepths;
    private final Map<Queue, AtomicLong> expiredClaims;
    private final Map<Queue, AtomicLong> oldestAges;
    private final Map<EvidenceScope, AtomicLong> incompleteBasisPoints;
    private final Map<StorageKind, AtomicLong> storageRecords;
    private final Map<StorageKind, AtomicLong> storageBacklog;
    private final AtomicLong health = new AtomicLong();

    /**
     * Registers all fixed status, queue, scope, and storage meter series.
     *
     * @param registry deployment-selected Micrometer registry
     */
    public TestRuntimeSloTelemetry(MeterRegistry registry) {
        MeterRegistry meters = Objects.requireNonNull(registry, "registry");
        enabled = true;
        executionOutcomes = enumGauges(meters, TestRunEvidence.Status.class,
                PREFIX + "execution.outcomes", "status");
        suiteOutcomes = enumGauges(meters, TestSuiteRunEvidence.Status.class,
                PREFIX + "suite.outcomes", "status");
        durableStates = enumGauges(meters, DurableTestExecutionCheckpoint.Status.class,
                PREFIX + "durable.executions", "status");
        engineStates = enumGauges(meters, ExecutionStatus.class,
                PREFIX + "engine.executions", "status");
        workStates = enumGauges(meters, WorkItemStatus.class,
                PREFIX + "work.items", "status");
        queueDepths = enumGauges(meters, Queue.class,
                PREFIX + "queue.depth", "queue");
        expiredClaims = enumGauges(meters, Queue.class,
                PREFIX + "lease.expired", "queue");
        oldestAges = enumGauges(meters, Queue.class,
                PREFIX + "queue.oldest.age", "queue");
        incompleteBasisPoints = enumGauges(meters, EvidenceScope.class,
                PREFIX + "evidence.incomplete.basis_points", "scope");
        storageRecords = enumGauges(meters, StorageKind.class,
                PREFIX + "storage.records", "kind");
        storageBacklog = enumGauges(meters, StorageKind.class,
                PREFIX + "storage.backlog", "kind");
        Gauge.builder(PREFIX + "health", health, AtomicLong::doubleValue)
                .description("Global test-runtime health: 1 healthy, -1 SLO violated, "
                        + "-2 store unavailable")
                .register(meters);
    }

    private TestRuntimeSloTelemetry() {
        enabled = false;
        executionOutcomes = Map.of();
        suiteOutcomes = Map.of();
        durableStates = Map.of();
        engineStates = Map.of();
        workStates = Map.of();
        queueDepths = Map.of();
        expiredClaims = Map.of();
        oldestAges = Map.of();
        incompleteBasisPoints = Map.of();
        storageRecords = Map.of();
        storageBacklog = Map.of();
    }

    /**
     * Creates a disabled adapter for isolated unit tests.
     *
     * @return no-op telemetry adapter
     */
    public static TestRuntimeSloTelemetry noop() {
        return new TestRuntimeSloTelemetry();
    }

    /**
     * Replaces all aggregate gauges from one transactionally consistent observation.
     *
     * @param snapshot database-clock operational snapshot
     * @param state assessed global health state
     * @param executionIncompleteBasisPoints recent incomplete child-evidence ratio
     * @param suiteIncompleteBasisPoints recent incomplete suite-evidence ratio
     */
    public void observe(
            DatabaseTestRuntimeSloControlPlane.OperationalSnapshot snapshot,
            TestRuntimeSloMonitor.State state,
            int executionIncompleteBasisPoints,
            int suiteIncompleteBasisPoints) {
        if (!enabled) {
            return;
        }
        Objects.requireNonNull(snapshot, "snapshot");
        replace(executionOutcomes, snapshot.executionOutcomes());
        replace(suiteOutcomes, snapshot.suiteOutcomes());
        replace(durableStates, snapshot.durableExecutionStates());
        replace(engineStates, snapshot.engineExecutionStates());
        replace(workStates, snapshot.workItemStates());
        observeQueue(Queue.SUITE_RUN, snapshot.suiteRuns(), snapshot.observedAt());
        observeQueue(Queue.DURABLE_CREATION, snapshot.durableCreations(), snapshot.observedAt());
        observeQueue(Queue.DURABLE_EXECUTION, snapshot.durableExecutions(), snapshot.observedAt());
        observeQueue(Queue.WORK_ITEM, snapshot.workItems(), snapshot.observedAt());
        incompleteBasisPoints.get(EvidenceScope.EXECUTION)
                .set(Math.max(0, executionIncompleteBasisPoints));
        incompleteBasisPoints.get(EvidenceScope.SUITE)
                .set(Math.max(0, suiteIncompleteBasisPoints));
        DatabaseTestRuntimeSloControlPlane.StorageSnapshot storage = snapshot.storage();
        storageRecords.get(StorageKind.EXECUTION).set(storage.executionRecords());
        storageRecords.get(StorageKind.SUITE).set(storage.suiteRecords());
        storageRecords.get(StorageKind.DURABLE_TERMINAL)
                .set(storage.terminalDurableExecutions());
        storageRecords.get(StorageKind.WORK_ITEM_TERMINAL).set(storage.terminalWorkItems());
        storageBacklog.get(StorageKind.EXECUTION).set(storage.expiredExecutionRecords());
        storageBacklog.get(StorageKind.SUITE).set(storage.expiredSuiteRecords());
        storageBacklog.get(StorageKind.DURABLE_TERMINAL)
                .set(storage.terminalDurableExecutions());
        storageBacklog.get(StorageKind.WORK_ITEM_TERMINAL).set(storage.terminalWorkItems());
        health.set(state == TestRuntimeSloMonitor.State.HEALTHY ? 1 : -1);
    }

    /** Marks the aggregate health gauge unavailable without exporting failure details. */
    public void observeStoreUnavailable() {
        if (enabled) {
            health.set(-2);
        }
    }

    private void observeQueue(
            Queue queue,
            DatabaseTestRuntimeSloControlPlane.QueueSnapshot snapshot,
            Instant observedAt) {
        queueDepths.get(queue).set(snapshot.depth());
        expiredClaims.get(queue).set(snapshot.expiredClaims());
        oldestAges.get(queue).set(ageSeconds(snapshot.oldestActivityAt(), observedAt));
    }

    private static long ageSeconds(Instant earlier, Instant observedAt) {
        if (earlier == null) {
            return -1;
        }
        return Math.max(0, Duration.between(earlier, observedAt).toSeconds());
    }

    private static <E extends Enum<E>> void replace(
            Map<E, AtomicLong> gauges,
            Map<E, Long> values) {
        gauges.forEach((key, gauge) -> gauge.set(values.getOrDefault(key, 0L)));
    }

    private static <E extends Enum<E>> Map<E, AtomicLong> enumGauges(
            MeterRegistry registry,
            Class<E> type,
            String name,
            String tagName) {
        EnumMap<E, AtomicLong> gauges = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) {
            AtomicLong gauge = new AtomicLong();
            gauges.put(value, gauge);
            Gauge.builder(name, gauge, AtomicLong::doubleValue)
                    .tag(tagName, value.name().toLowerCase(java.util.Locale.ROOT))
                    .register(registry);
        }
        return Map.copyOf(gauges);
    }

    private enum Queue {
        SUITE_RUN,
        DURABLE_CREATION,
        DURABLE_EXECUTION,
        WORK_ITEM
    }

    private enum EvidenceScope {
        EXECUTION,
        SUITE
    }

    private enum StorageKind {
        EXECUTION,
        SUITE,
        DURABLE_TERMINAL,
        WORK_ITEM_TERMINAL
    }
}
