package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryExecutionResult;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Lane;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneDescriptor;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneKey;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Snapshot;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.Acquisition;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.AcquisitionCommand;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.AcquisitionStatus;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.CompletionStatus;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.FleetManifest;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.Lease;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
 * and same-generation replacement of service or resolver objects. Its compatible local mode keeps
 * one process-local cursor. Its durable mode acquires one fixed partition through a database-clock
 * coordinator, resumes after that partition's durable cursor, and heartbeats independently of slow
 * lane execution. A fenced completion fails the cycle and leaves the prior cursor for at-least-once
 * replay; a fatal cycle explicitly abandons its latest lease without moving that cursor. Each
 * lane's own ceremony journal remains the execution and write-fencing authority.</p>
 */
public final class ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker
        implements AutoCloseable {

    private final ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory;
    private final String workerId;
    private final Policy policy;
    private final ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator coordinator;
    private final String fleetId;
    private final int partitionCount;
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
        this(inventory, workerId, policy, null, null, 0);
    }

    /**
     * Creates a cross-replica worker with durable partition assignment and cursor fairness.
     *
     * <p>Partition count is a fleet identity invariant. Changing it requires a new
     * {@code fleetId}; this prevents a rolling deployment from silently remapping lanes. The
     * coordinator lease is scheduling authority only. Long lane execution may outlive it, in which
     * case stale completion fails and the lane-level ceremony journal prevents duplicate writes.</p>
     *
     * @param inventory non-blocking already-authorized local lane inventory
     * @param workerId stable pre-authenticated recovery worker identity
     * @param policy bounded lane budget and database lease duration
     * @param coordinator durable database-clock partition coordinator
     * @param fleetId stable deployment-wide scheduler identity
     * @param partitionCount fixed partition count from 1 through 64
     */
    public ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory,
            String workerId,
            Policy policy,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator coordinator,
            String fleetId,
            int partitionCount) {
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
        this.coordinator = coordinator;
        if (coordinator == null) {
            if (fleetId != null || partitionCount != 0) {
                throw new IllegalArgumentException(
                        "Local recovery fleet worker cannot declare durable topology");
            }
            this.fleetId = null;
            this.partitionCount = 0;
        } else {
            if (!coordinator.durable()) {
                throw new IllegalArgumentException(
                        "Recovery fleet coordinator must be durable");
            }
            FleetManifest validated = new FleetManifest(FleetManifest.SCHEMA_VERSION,
                    fleetId, 1L, "sha256:" + "0".repeat(64), partitionCount);
            this.fleetId = validated.fleetId();
            this.partitionCount = validated.partitionCount();
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
            CycleExecution execution = coordinator == null
                    ? runLocal(current) : runDurable(current);
            CycleResult completed = new CycleResult(CycleResult.SCHEMA_VERSION,
                    current.generation(), execution.disposition(), execution.lanes());
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

    private CycleExecution runLocal(Snapshot current) {
        List<Lane> lanes = current.lanes();
        List<LaneResult> results = new ArrayList<>();
        int visits = Math.min(policy.maximumLanesPerCycle(), lanes.size());
        int start = indexAfter(lanes, cursorExclusive);
        for (int offset = 0; offset < visits; offset++) {
            Lane lane = lanes.get((start + offset) % lanes.size());
            try {
                visit(lane, results);
            } finally {
                cursorExclusive = lane.key();
            }
        }
        return CycleExecution.completed(results);
    }

    private CycleExecution runDurable(Snapshot current) {
        FleetManifest manifest = FleetManifest.from(fleetId, current, partitionCount);
        AcquisitionCommand command = new AcquisitionCommand(AcquisitionCommand.SCHEMA_VERSION,
                manifest, workerId, UUID.randomUUID().toString().replace("-", ""),
                policy.leaseDurationSeconds());
        Acquisition acquisition = Objects.requireNonNull(
                coordinator.acquire(command), "fleet acquisition");
        if (acquisition.status() == AcquisitionStatus.BUSY) {
            return CycleExecution.busy();
        }
        Lease acquiredLease = exactAcquisition(command, acquisition.lease());
        List<Lane> partitionLanes = current.lanes().stream()
                .filter(lane -> acquiredLease.owns(lane.key()))
                .toList();
        List<LaneResult> results = new ArrayList<>();
        int visits = Math.min(policy.maximumLanesPerCycle(), partitionLanes.size());
        int start = indexAfter(partitionLanes, acquiredLease.cursorExclusive());
        LeaseHeartbeat heartbeat;
        try {
            heartbeat = new LeaseHeartbeat(coordinator, acquiredLease);
        } catch (RuntimeException | Error startupFailure) {
            try {
                coordinator.abandon(acquiredLease);
            } catch (RuntimeException | Error cleanupFailure) {
                startupFailure.addSuppressed(cleanupFailure);
            }
            throw startupFailure;
        }
        LaneKey lastAttempted = null;
        try {
            for (int offset = 0; offset < visits; offset++) {
                if (offset > 0) {
                    heartbeat.renewNow();
                }
                heartbeat.assertHealthy();
                Lane lane = partitionLanes.get((start + offset) % partitionLanes.size());
                visit(lane, results);
                lastAttempted = lane.key();
                heartbeat.assertHealthy();
            }
            Lease latest = heartbeat.stop();
            if (coordinator.complete(latest, lastAttempted) != CompletionStatus.COMPLETED) {
                throw new IllegalStateException(
                        "Recovery fleet partition completion was fenced");
            }
            return CycleExecution.completed(results);
        } catch (RuntimeException | Error failure) {
            Lease latest = heartbeat.stopAfterFailure(failure);
            try {
                coordinator.abandon(latest);
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static Lease exactAcquisition(AcquisitionCommand command, Lease lease) {
        Lease safe = Objects.requireNonNull(lease, "fleet lease");
        if (!safe.manifest().equals(command.manifest())
                || !safe.workerId().equals(command.workerId())
                || !safe.commandId().equals(command.commandId())
                || safe.leaseDurationSeconds() != command.leaseDurationSeconds()) {
            throw new IllegalStateException(
                    "Recovery fleet coordinator returned a drifted acquisition");
        }
        return safe;
    }

    private void visit(Lane lane, List<LaneResult> results) {
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
        }
    }

    private record CycleExecution(CycleDisposition disposition, List<LaneResult> lanes) {

        private CycleExecution {
            disposition = Objects.requireNonNull(disposition, "disposition");
            lanes = List.copyOf(Objects.requireNonNull(lanes, "lanes"));
        }

        private static CycleExecution completed(List<LaneResult> lanes) {
            return new CycleExecution(CycleDisposition.COMPLETED, lanes);
        }

        private static CycleExecution busy() {
            return new CycleExecution(CycleDisposition.COORDINATOR_BUSY, List.of());
        }
    }

    private static final class LeaseHeartbeat {

        private static final long STOP_TIMEOUT_MILLIS = 5_000L;

        private final ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator coordinator;
        private final AtomicReference<Lease> latest;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean stopping = new AtomicBoolean();
        private final long intervalMillis;
        private final Thread thread;

        private LeaseHeartbeat(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator coordinator,
                Lease initial) {
            this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
            Lease safe = Objects.requireNonNull(initial, "initial");
            this.latest = new AtomicReference<>(safe);
            this.intervalMillis = Math.max(1_000L,
                    Math.multiplyExact(safe.leaseDurationSeconds(), 1_000L) / 3L);
            this.thread = Thread.ofPlatform().daemon(true)
                    .name("bootstrap-root-recovery-fleet-heartbeat")
                    .start(this::run);
        }

        private void run() {
            while (!stopping.get()) {
                try {
                    Thread.sleep(intervalMillis);
                } catch (InterruptedException interrupted) {
                    if (stopping.get()) {
                        return;
                    }
                    Thread.currentThread().interrupt();
                    failure.compareAndSet(null, new IllegalStateException(
                            "Recovery fleet partition heartbeat was interrupted", interrupted));
                    return;
                }
                renewNow();
            }
        }

        private synchronized void renewNow() {
            if (stopping.get() || failure.get() != null) {
                return;
            }
            try {
                Lease current = latest.get();
                Lease renewed = coordinator.renew(current).orElseThrow(() ->
                        new IllegalStateException(
                                "Recovery fleet partition lease was fenced during cycle"));
                if (!renewed.manifest().equals(current.manifest())
                        || renewed.partitionId() != current.partitionId()
                        || renewed.fleetEpoch() != current.fleetEpoch()
                        || renewed.leaseEpoch() != current.leaseEpoch()
                        || !renewed.leaseToken().equals(current.leaseToken())
                        || !renewed.workerId().equals(current.workerId())
                        || !renewed.commandId().equals(current.commandId())
                        || renewed.leaseDurationSeconds() != current.leaseDurationSeconds()
                        || !Objects.equals(renewed.cursorExclusive(), current.cursorExclusive())
                        || !renewed.leaseExpiresAt().isAfter(current.leaseExpiresAt())) {
                    throw new IllegalStateException(
                            "Recovery fleet coordinator returned a drifted renewal");
                }
                latest.set(renewed);
            } catch (RuntimeException | Error renewalFailure) {
                failure.compareAndSet(null, renewalFailure);
            }
        }

        private void assertHealthy() {
            Throwable heartbeatFailure = failure.get();
            if (heartbeatFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (heartbeatFailure instanceof Error fatalFailure) {
                throw fatalFailure;
            }
        }

        private Lease stop() {
            stopThread();
            assertHealthy();
            return latest.get();
        }

        private Lease stopAfterFailure(Throwable primary) {
            try {
                stopThread();
            } catch (RuntimeException stopFailure) {
                primary.addSuppressed(stopFailure);
            }
            Throwable heartbeatFailure = failure.get();
            if (heartbeatFailure != null && heartbeatFailure != primary) {
                primary.addSuppressed(heartbeatFailure);
            }
            return latest.get();
        }

        private void stopThread() {
            if (stopping.compareAndSet(false, true)) {
                thread.interrupt();
            }
            try {
                thread.join(STOP_TIMEOUT_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while stopping recovery fleet heartbeat", interrupted);
            }
            if (thread.isAlive()) {
                throw new IllegalStateException(
                        "Recovery fleet partition heartbeat did not stop in time");
            }
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

    /** Bounded completion state of one fleet worker cycle. */
    public enum CycleDisposition {
        /** The local cycle or acquired durable partition completed and committed normally. */
        COMPLETED,
        /** Every durable partition was actively leased, so no lane was attempted. */
        COORDINATOR_BUSY
    }

    /**
     * Payload-free result of one bounded fleet cycle.
     *
     * @param schemaVersion cycle result protocol generation
     * @param inventoryGeneration exact inventory generation used by the cycle
     * @param disposition normal completion or durable coordinator contention
     * @param lanes actual bounded lane visit order and outcomes
     */
    public record CycleResult(
            String schemaVersion,
            long inventoryGeneration,
            CycleDisposition disposition,
            List<LaneResult> lanes) {

        /** Current fleet cycle result schema. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetCycle.v2";

        /** Defensively copies results and enforces disposition-dependent lane shape. */
        public CycleResult {
            schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
            disposition = Objects.requireNonNull(disposition, "disposition");
            lanes = List.copyOf(Objects.requireNonNull(lanes, "lanes"));
            Set<LaneKey> keys = new HashSet<>();
            if (!SCHEMA_VERSION.equals(schemaVersion) || inventoryGeneration < 1L
                    || disposition == CycleDisposition.COORDINATOR_BUSY && !lanes.isEmpty()
                    || lanes.size()
                    > ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.MAXIMUM_LANES
                    || lanes.stream().anyMatch(result -> result == null
                    || !keys.add(result.laneKey()))) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery fleet cycle result is invalid");
            }
        }

        /**
         * Creates a normally completed cycle for source compatibility with local schedulers.
         *
         * @param schemaVersion cycle result protocol generation
         * @param inventoryGeneration exact inventory generation used by the cycle
         * @param lanes actual bounded lane visit order and outcomes
         */
        public CycleResult(
                String schemaVersion, long inventoryGeneration, List<LaneResult> lanes) {
            this(schemaVersion, inventoryGeneration, CycleDisposition.COMPLETED, lanes);
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
