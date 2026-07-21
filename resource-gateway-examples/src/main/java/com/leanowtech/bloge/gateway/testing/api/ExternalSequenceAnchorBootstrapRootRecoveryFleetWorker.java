package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryExecutionResult;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Lane;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneDescriptor;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneKey;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Snapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded fair worker over an authorized bootstrap-root recovery lane inventory.
 *
 * <p>Each cycle captures one immutable inventory generation, starts after the last attempted
 * scope/root-set key, and visits at most the configured lane budget. The cursor advances even when
 * a lane throws, so a poison prefix cannot starve later root sets. Per-lane runtime failures are
 * collapsed into bounded results and do not abort the remaining cycle; {@link Error} and inventory
 * failures remain visible to the caller.</p>
 *
 * <p>The worker rejects inventory generation rollback, descriptor drift at the same generation,
 * and same-generation replacement of service or resolver objects. It relies on each lane's
 * database journal for acquisition, retry timing, attempt budget, and fencing. The cursor is
 * process-local, so this kernel provides local fairness only; it does not claim durable fleet
 * sharding, cross-replica fairness, signed inventory governance, or background scheduling.</p>
 */
public final class ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker
        implements AutoCloseable {

    private final ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory;
    private final String workerId;
    private final Policy policy;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong cycleCount = new AtomicLong();
    private final AtomicLong cycleFailureCount = new AtomicLong();
    private final AtomicLong laneAttemptCount = new AtomicLong();
    private final AtomicLong laneAcquiredCount = new AtomicLong();
    private final AtomicLong laneFailureCount = new AtomicLong();
    private volatile boolean active;
    private volatile boolean lastCycleFailed;
    private volatile boolean lastCompletedCycleHadLaneFailures;
    private volatile long lastInventoryGeneration;
    private InventoryState acceptedInventory;
    private LaneKey cursorExclusive;

    /**
     * Creates a local fleet worker with conservative bounded defaults.
     *
     * @param inventory non-blocking already-authorized local lane inventory
     * @param workerId stable pre-authenticated recovery worker identity
     */
    public ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory,
            String workerId) {
        this(inventory, workerId, Policy.DEFAULT);
    }

    /**
     * Creates a local fleet worker with an explicit cycle and lease policy.
     *
     * @param inventory non-blocking already-authorized local lane inventory
     * @param workerId stable pre-authenticated recovery worker identity
     * @param policy bounded lane budget and database lease duration
     */
    public ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory,
            String workerId,
            Policy policy) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.policy = Objects.requireNonNull(policy, "policy");
        new ExternalSequenceAnchorBootstrapRootCeremonyJournal.RecoveryAcquisitionCommand(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.RecoveryAcquisitionCommand
                        .SCHEMA_VERSION,
                workerId, policy.leaseDurationSeconds());
        if (policy.leaseDurationSeconds() < 3L) {
            throw new IllegalArgumentException(
                    "Ceremony fleet auto-heartbeat lease must be at least three seconds");
        }
    }

    /**
     * Runs one bounded cycle over a generation-stable canonical lane snapshot.
     *
     * @return per-lane payload-free outcomes in actual fair-cursor visit order
     */
    public synchronized CycleResult runCycle() {
        requireOpen();
        active = true;
        cycleCount.incrementAndGet();
        try {
            Snapshot current = Objects.requireNonNull(inventory.snapshot(), "inventory snapshot");
            accept(current);
            List<Lane> lanes = current.lanes();
            List<LaneResult> results = new ArrayList<>();
            int visits = Math.min(policy.maximumLanesPerCycle(), lanes.size());
            int start = indexAfter(lanes, cursorExclusive);
            for (int offset = 0; offset < visits; offset++) {
                Lane lane = lanes.get((start + offset) % lanes.size());
                laneAttemptCount.incrementAndGet();
                try {
                    RecoveryExecutionResult result = lane.service().recover(
                            workerId, policy.leaseDurationSeconds(), lane.authorityResolver());
                    RecoveryStatus status = Objects.requireNonNull(
                            result, "recovery result").status();
                    ExecutionStatus executionStatus = result.execution() == null
                            ? null : result.execution().status();
                    if (status == RecoveryStatus.EXECUTED) {
                        laneAcquiredCount.incrementAndGet();
                    }
                    results.add(LaneResult.completed(lane.key(), status, executionStatus));
                } catch (RuntimeException laneFailure) {
                    laneFailureCount.incrementAndGet();
                    results.add(LaneResult.failed(lane.key()));
                } finally {
                    cursorExclusive = lane.key();
                }
            }
            CycleResult completed = new CycleResult(CycleResult.SCHEMA_VERSION,
                    current.generation(), results);
            lastInventoryGeneration = current.generation();
            lastCycleFailed = false;
            lastCompletedCycleHadLaneFailures = completed.failedCount() > 0;
            return completed;
        } catch (RuntimeException | Error cycleFailure) {
            cycleFailureCount.incrementAndGet();
            lastCycleFailed = true;
            throw cycleFailure;
        } finally {
            active = false;
        }
    }

    /**
     * Returns aggregate process-local state without scope, root-set, resolver, or failure details.
     *
     * @return immutable payload-free worker projection
     */
    public RuntimeSnapshot runtimeSnapshot() {
        // Read dependent values before their parents; writers publish parents first, so observing
        // a child guarantees its parent is visible to the later read without taking the cycle lock.
        boolean latestCycleFailed = lastCycleFailed;
        boolean latestCompletedCycleHadLaneFailures =
                lastCompletedCycleHadLaneFailures;
        long failedCycles = cycleFailureCount.get();
        long acquiredLanes = laneAcquiredCount.get();
        long failedLanes = laneFailureCount.get();
        return new RuntimeSnapshot(RuntimeSnapshot.SCHEMA_VERSION, closed.get(), active,
                cycleCount.get(), failedCycles, laneAttemptCount.get(),
                acquiredLanes, failedLanes, latestCycleFailed,
                latestCompletedCycleHadLaneFailures, lastInventoryGeneration);
    }

    /**
     * Closes the cycle admission gate after any in-progress synchronized cycle returns.
     *
     * <p>The worker does not own inventory, ceremony services, resolvers, or their provider
     * resources and therefore does not close them.</p>
     */
    @Override
    public synchronized void close() {
        closed.set(true);
    }

    private void accept(Snapshot current) {
        InventoryState next = new InventoryState(current);
        if (acceptedInventory != null) {
            if (current.generation() < acceptedInventory.generation()) {
                throw new IllegalStateException(
                        "Bootstrap-root recovery fleet inventory generation rolled back");
            }
            if (current.generation() == acceptedInventory.generation()
                    && !acceptedInventory.sameGeneration(next)) {
                throw new IllegalStateException(
                        "Bootstrap-root recovery fleet inventory generation drifted");
            }
        }
        if (acceptedInventory == null
                || current.generation() > acceptedInventory.generation()) {
            acceptedInventory = next;
        }
    }

    private static int indexAfter(List<Lane> lanes, LaneKey cursor) {
        if (lanes.isEmpty() || cursor == null) {
            return 0;
        }
        int low = 0;
        int high = lanes.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (lanes.get(middle).key().compareTo(cursor) <= 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low == lanes.size() ? 0 : low;
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Bootstrap-root recovery fleet worker is closed");
        }
    }

    private record InventoryState(long generation, List<Lane> lanes,
                                  List<LaneDescriptor> descriptors) {

        private InventoryState(Snapshot snapshot) {
            this(snapshot.generation(), snapshot.lanes(), snapshot.descriptors());
        }

        private InventoryState {
            lanes = List.copyOf(lanes);
            descriptors = List.copyOf(descriptors);
        }

        private boolean sameGeneration(InventoryState other) {
            if (!descriptors.equals(other.descriptors) || lanes.size() != other.lanes.size()) {
                return false;
            }
            for (int index = 0; index < lanes.size(); index++) {
                Lane left = lanes.get(index);
                Lane right = other.lanes.get(index);
                if (left.service() != right.service()
                        || left.authorityResolver() != right.authorityResolver()) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Bounded fleet cycle policy.
     *
     * @param leaseDurationSeconds database-clock auto-renewed lane lease from 3 through 300 seconds
     * @param maximumLanesPerCycle maximum distinct lanes visited in one cycle
     */
    public record Policy(long leaseDurationSeconds, int maximumLanesPerCycle) {

        /** Conservative default of a 30-second lease and 16 lanes per cycle. */
        public static final Policy DEFAULT = new Policy(30L, 16);

        /** Enforces database lease and local work bounds. */
        public Policy {
            if (leaseDurationSeconds < 3L || leaseDurationSeconds > 300L
                    || maximumLanesPerCycle < 1
                    || maximumLanesPerCycle
                    > ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.MAXIMUM_LANES) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery fleet policy is invalid");
            }
        }
    }

    /**
     * One bounded lane outcome without provider diagnostics.
     *
     * @param laneKey public scope/root-set key
     * @param status recovery poll status, absent only for a collapsed runtime failure
     * @param executionStatus execution result only for an acquired attempt
     * @param runtimeFailure whether the lane threw a runtime failure
     */
    public record LaneResult(
            LaneKey laneKey,
            RecoveryStatus status,
            ExecutionStatus executionStatus,
            boolean runtimeFailure) {

        /** Enforces status-dependent execution and collapsed failure shape. */
        public LaneResult {
            laneKey = Objects.requireNonNull(laneKey, "laneKey");
            if (runtimeFailure != (status == null)
                    || (status == RecoveryStatus.EXECUTED) != (executionStatus != null)) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery fleet lane result is invalid");
            }
        }

        private static LaneResult completed(
                LaneKey laneKey, RecoveryStatus status, ExecutionStatus executionStatus) {
            return new LaneResult(laneKey, Objects.requireNonNull(status, "status"),
                    executionStatus, false);
        }

        private static LaneResult failed(LaneKey laneKey) {
            return new LaneResult(laneKey, null, null, true);
        }
    }

    /**
     * Payload-free result of one bounded fleet cycle.
     *
     * @param schemaVersion cycle result protocol generation
     * @param inventoryGeneration exact inventory generation used by the cycle
     * @param lanes actual bounded lane visit order and outcomes
     */
    public record CycleResult(
            String schemaVersion,
            long inventoryGeneration,
            List<LaneResult> lanes) {

        /** Current fleet cycle result schema. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetCycle.v1";

        /** Defensively copies results and rejects duplicate lane visits. */
        public CycleResult {
            schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
            lanes = List.copyOf(Objects.requireNonNull(lanes, "lanes"));
            Set<LaneKey> keys = new HashSet<>();
            if (!SCHEMA_VERSION.equals(schemaVersion) || inventoryGeneration < 1L
                    || lanes.size()
                    > ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.MAXIMUM_LANES
                    || lanes.stream().anyMatch(result -> result == null
                    || !keys.add(result.laneKey()))) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery fleet cycle result is invalid");
            }
        }

        /**
         * Returns the number of lanes visited by this cycle.
         *
         * @return bounded lane visit count
         */
        public int attemptedCount() {
            return lanes.size();
        }

        /**
         * Returns the number of lanes that acquired and ran a database-fenced attempt.
         *
         * @return acquired lane count
         */
        public long acquiredCount() {
            return lanes.stream().filter(result -> result.status() == RecoveryStatus.EXECUTED)
                    .count();
        }

        /**
         * Returns the number of collapsed per-lane runtime failures.
         *
         * @return isolated runtime failure count
         */
        public long failedCount() {
            return lanes.stream().filter(LaneResult::runtimeFailure).count();
        }
    }

    /**
     * Aggregate process-local fleet worker projection.
     *
     * @param schemaVersion runtime snapshot generation
     * @param closed whether new cycles are rejected
     * @param active whether one synchronized cycle is in progress
     * @param cycleCount started cycles
     * @param cycleFailureCount cycles terminated by inventory, invariant, or fatal failure
     * @param laneAttemptCount visited lanes
     * @param laneAcquiredCount lanes that acquired and ran an attempt
     * @param laneFailureCount collapsed per-lane runtime failures
     * @param lastCycleFailed whether the latest terminated cycle threw
     * @param lastCompletedCycleHadLaneFailures whether the latest completed cycle isolated failures
     * @param lastInventoryGeneration latest successfully completed inventory generation, or zero
     */
    public record RuntimeSnapshot(
            String schemaVersion,
            boolean closed,
            boolean active,
            long cycleCount,
            long cycleFailureCount,
            long laneAttemptCount,
            long laneAcquiredCount,
            long laneFailureCount,
            boolean lastCycleFailed,
            boolean lastCompletedCycleHadLaneFailures,
            long lastInventoryGeneration) {

        /** Current fleet worker runtime snapshot schema. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetRuntimeSnapshot.v1";

        /** Enforces monotonic aggregate counter and state relationships. */
        public RuntimeSnapshot {
            schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
            if (!SCHEMA_VERSION.equals(schemaVersion) || cycleCount < 0L
                    || cycleFailureCount < 0L || cycleFailureCount > cycleCount
                    || laneAttemptCount < 0L || laneAcquiredCount < 0L
                    || laneAcquiredCount > laneAttemptCount || laneFailureCount < 0L
                    || laneFailureCount > laneAttemptCount
                    || lastCycleFailed && cycleFailureCount == 0L
                    || lastCompletedCycleHadLaneFailures && laneFailureCount == 0L
                    || lastInventoryGeneration < 0L || closed && active) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery fleet runtime snapshot is invalid");
            }
        }
    }
}
