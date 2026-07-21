package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Descriptor;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Observation;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.Snapshot;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.RuntimeSnapshot;

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Machine-readable, identity-free readiness of one local bootstrap-root recovery fleet.
 *
 * <p>The projection reads only immutable process-local authority, worker, and scheduler snapshots.
 * It performs no network, database, lane-resolution, provider, or payload operation. Two authority
 * observations bracket the other reads so a refresh that tears the projection is reported as
 * {@link Status#INCONSISTENT}, never as a healthy mixed-generation capability.</p>
 *
 * <p>Composition discovery is intentionally outside this record. Callers use the closed disabled,
 * incomplete, ambiguous, or unattested factories before invoking {@link #project} with exactly one
 * externally attested authority, worker, and scheduler.</p>
 *
 * @param schemaVersion capability protocol generation
 * @param configured whether any local recovery-fleet composition is present
 * @param ready whether new authorized recovery polling is currently safe
 * @param status bounded readiness reason
 * @param externallyAttested whether an external signature authority governs the inventory
 * @param inventoryAvailable whether the exact current inventory is locally admissible
 * @param sourceType bounded authority source type without identity
 * @param inventoryGeneration current signed inventory generation
 * @param laneCount current signed lane cardinality
 * @param dynamicInventory whether the source is the witnessed dynamic HTTPS authority
 * @param automaticRefresh whether the local authority refreshes without restart
 * @param signedRevocation whether signed inventory revocation is enforced
 * @param witnessedPublications whether an independent witness signs publication ordering
 * @param durablePublicationFloor whether publication ordering survives process restart
 * @param externallyAnchoredPublicationFloor whether publication ordering is externally anchored
 * @param byzantineQuorumAnchoredPublicationFloor whether the external anchor has Byzantine quorum
 * @param managedTrustRootRefresh whether runtime verification keys refresh atomically
 * @param managedTrustRootAvailable whether the current managed dual-key generation is usable
 * @param managedTrustRootStatus bounded managed-root lifecycle status or {@code DISABLED}
 * @param managedTrustRootSequence current accepted managed-root sequence, or zero when disabled
 * @param atomicDualTrustRootPublication whether both runtime domains rotate as one generation
 * @param durableTrustRootFloor whether managed-root ordering survives process restart
 * @param externallyAnchoredTrustRootFloor whether managed-root ordering is externally anchored
 * @param byzantineQuorumAnchoredTrustRootFloor whether that root anchor has Byzantine quorum
 * @param externalInventoryNonEquivocation whether every mutable inventory stream is externally ordered
 * @param byzantineQuorumInventoryNonEquivocation whether every stream has Byzantine quorum ordering
 * @param schedulerActive whether a scheduler cycle is currently active
 * @param schedulerOverdue whether the scheduler exceeded its bounded progress budget
 * @param pollCount aggregate local scheduler polls
 * @param pollFailureCount aggregate local scheduler poll failures
 * @param cycleCount aggregate local worker cycles
 * @param cycleFailureCount aggregate local worker cycle failures
 */
public record ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability(
        String schemaVersion,
        boolean configured,
        boolean ready,
        Status status,
        boolean externallyAttested,
        boolean inventoryAvailable,
        String sourceType,
        long inventoryGeneration,
        int laneCount,
        boolean dynamicInventory,
        boolean automaticRefresh,
        boolean signedRevocation,
        boolean witnessedPublications,
        boolean durablePublicationFloor,
        boolean externallyAnchoredPublicationFloor,
        boolean byzantineQuorumAnchoredPublicationFloor,
        boolean managedTrustRootRefresh,
        boolean managedTrustRootAvailable,
        String managedTrustRootStatus,
        long managedTrustRootSequence,
        boolean atomicDualTrustRootPublication,
        boolean durableTrustRootFloor,
        boolean externallyAnchoredTrustRootFloor,
        boolean byzantineQuorumAnchoredTrustRootFloor,
        boolean externalInventoryNonEquivocation,
        boolean byzantineQuorumInventoryNonEquivocation,
        boolean schedulerActive,
        boolean schedulerOverdue,
        long pollCount,
        long pollFailureCount,
        long cycleCount,
        long cycleFailureCount) {

    /** Original capability protocol generation retained as a frozen historical reference. */
    public static final String SCHEMA_VERSION_V1 =
            "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetCapability.v1";

    /** Current capability protocol generation with managed-root operational truth. */
    public static final String SCHEMA_VERSION =
            "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetCapability.v2";

    private static final Pattern SOURCE_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");
    private static final Set<String> MANAGED_ROOT_STATUSES = Set.of(
            "DISABLED", "HEALTHY", "CLOSED", "REFRESH_UNAVAILABLE", "SOURCE_EXPIRED",
            "EXPIRED", "DEPLOYMENT_THRESHOLD_UNAVAILABLE",
            "WITNESS_THRESHOLD_UNAVAILABLE");

    /** Enforces status, trust, anchoring, and aggregate-counter relationships. */
    public ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability {
        schemaVersion = normalized(schemaVersion);
        sourceType = normalized(sourceType);
        managedTrustRootStatus = normalized(managedTrustRootStatus);
        status = Objects.requireNonNull(status, "status");
        boolean disabled = status == Status.DISABLED;
        boolean hasInventoryIdentity = !sourceType.isBlank() || inventoryGeneration != 0L
                || laneCount != 0;
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || configured == disabled
                || ready != (status == Status.READY)
                || !sourceType.isBlank() && !SOURCE_TYPE.matcher(sourceType).matches()
                || inventoryGeneration < 0L || laneCount < 0
                || laneCount
                > ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.MAXIMUM_LANES
                || inventoryAvailable && (!externallyAttested || sourceType.isBlank()
                || inventoryGeneration < 1L)
                || ready && (!inventoryAvailable || !externallyAttested
                || sourceType.isBlank() || inventoryGeneration < 1L || schedulerOverdue)
                || !configured && (externallyAttested || inventoryAvailable
                || hasInventoryIdentity || dynamicInventory || automaticRefresh
                || signedRevocation || witnessedPublications || durablePublicationFloor
                || externallyAnchoredPublicationFloor
                || byzantineQuorumAnchoredPublicationFloor || managedTrustRootRefresh
                || managedTrustRootAvailable || !"DISABLED".equals(managedTrustRootStatus)
                || managedTrustRootSequence != 0L || atomicDualTrustRootPublication
                || durableTrustRootFloor || externallyAnchoredTrustRootFloor
                || byzantineQuorumAnchoredTrustRootFloor
                || externalInventoryNonEquivocation
                || byzantineQuorumInventoryNonEquivocation || schedulerActive
                || schedulerOverdue || pollCount != 0L || pollFailureCount != 0L
                || cycleCount != 0L || cycleFailureCount != 0L)
                || dynamicInventory
                != DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                .SOURCE_TYPE.equals(sourceType)
                || (automaticRefresh || signedRevocation || witnessedPublications
                || durablePublicationFloor) && !externallyAttested
                || externallyAnchoredPublicationFloor && !durablePublicationFloor
                || byzantineQuorumAnchoredPublicationFloor
                && !externallyAnchoredPublicationFloor
                || managedTrustRootRefresh && !dynamicInventory
                || managedTrustRootRefresh != atomicDualTrustRootPublication
                || managedTrustRootRefresh != durableTrustRootFloor
                || managedTrustRootRefresh && (managedTrustRootSequence < 1L
                || !MANAGED_ROOT_STATUSES.contains(managedTrustRootStatus)
                || "DISABLED".equals(managedTrustRootStatus)
                || managedTrustRootAvailable != "HEALTHY".equals(managedTrustRootStatus))
                || !managedTrustRootRefresh && (managedTrustRootAvailable
                || !"DISABLED".equals(managedTrustRootStatus)
                || managedTrustRootSequence != 0L || externallyAnchoredTrustRootFloor
                || byzantineQuorumAnchoredTrustRootFloor)
                || externallyAnchoredTrustRootFloor && !durableTrustRootFloor
                || byzantineQuorumAnchoredTrustRootFloor
                && !externallyAnchoredTrustRootFloor
                || externalInventoryNonEquivocation
                && (!externallyAnchoredPublicationFloor
                || managedTrustRootRefresh && !externallyAnchoredTrustRootFloor)
                || byzantineQuorumInventoryNonEquivocation
                && (!externalInventoryNonEquivocation
                || !byzantineQuorumAnchoredPublicationFloor
                || managedTrustRootRefresh && !byzantineQuorumAnchoredTrustRootFloor)
                || pollCount < 0L || pollFailureCount < 0L || pollFailureCount > pollCount
                || cycleCount < 0L || cycleFailureCount < 0L
                || cycleFailureCount > cycleCount) {
            throw new IllegalArgumentException(
                    "Bootstrap-root recovery fleet capability is invalid");
        }
    }

    /**
     * Preserves the pre-managed Java construction surface with a disabled-root projection.
     *
     * @param schemaVersion current schema version
     * @param configured whether a local fleet is composed
     * @param ready whether polling is admitted
     * @param status bounded readiness state
     * @param externallyAttested whether inventory signatures are authoritative
     * @param inventoryAvailable whether the inventory is currently usable
     * @param sourceType bounded inventory source type
     * @param inventoryGeneration current inventory generation
     * @param laneCount inventory lane cardinality
     * @param dynamicInventory whether witnessed HTTPS refresh is active
     * @param automaticRefresh whether inventory refresh is automatic
     * @param signedRevocation whether signed revocation is enforced
     * @param witnessedPublications whether publication ordering is witnessed
     * @param durablePublicationFloor whether publication order survives restart
     * @param externallyAnchoredPublicationFloor whether publication order is external
     * @param byzantineQuorumAnchoredPublicationFloor whether publication order has quorum
     * @param schedulerActive whether one scheduler cycle is active
     * @param schedulerOverdue whether scheduler progress is overdue
     * @param pollCount process-local poll count
     * @param pollFailureCount process-local poll failure count
     * @param cycleCount process-local worker cycle count
     * @param cycleFailureCount process-local worker cycle failure count
     */
    public ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability(
            String schemaVersion,
            boolean configured,
            boolean ready,
            Status status,
            boolean externallyAttested,
            boolean inventoryAvailable,
            String sourceType,
            long inventoryGeneration,
            int laneCount,
            boolean dynamicInventory,
            boolean automaticRefresh,
            boolean signedRevocation,
            boolean witnessedPublications,
            boolean durablePublicationFloor,
            boolean externallyAnchoredPublicationFloor,
            boolean byzantineQuorumAnchoredPublicationFloor,
            boolean schedulerActive,
            boolean schedulerOverdue,
            long pollCount,
            long pollFailureCount,
            long cycleCount,
            long cycleFailureCount) {
        this(schemaVersion, configured, ready, status, externallyAttested,
                inventoryAvailable, sourceType, inventoryGeneration, laneCount,
                dynamicInventory, automaticRefresh, signedRevocation,
                witnessedPublications, durablePublicationFloor,
                externallyAnchoredPublicationFloor,
                byzantineQuorumAnchoredPublicationFloor, false, false, "DISABLED", 0L,
                false, false, false, false, externallyAnchoredPublicationFloor,
                byzantineQuorumAnchoredPublicationFloor, schedulerActive, schedulerOverdue,
                pollCount, pollFailureCount, cycleCount, cycleFailureCount);
    }

    /**
     * Projects one exact, already-assembled fleet without performing external I/O.
     *
     * @param authority unique externally attested local inventory authority
     * @param worker unique bounded recovery worker
     * @param scheduler unique fixed-delay scheduler
     * @return current fail-closed capability truth
     */
    public static ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability project(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority authority,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker worker,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler scheduler) {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(scheduler, "scheduler");
        return project(authority::observation, authority::descriptor, worker::runtimeSnapshot,
                scheduler::snapshot);
    }

    static ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability project(
            Supplier<Observation> observations,
            Supplier<Descriptor> descriptors,
            Supplier<RuntimeSnapshot> workers,
            Supplier<Snapshot> schedulers) {
        try {
            Observation before = Objects.requireNonNull(
                    observations.get(), "authority observation");
            Descriptor descriptor = Objects.requireNonNull(
                    descriptors.get(), "authority descriptor");
            RuntimeSnapshot worker = Objects.requireNonNull(
                    workers.get(), "worker snapshot");
            Snapshot scheduler = Objects.requireNonNull(
                    schedulers.get(), "scheduler snapshot");
            Observation after = Objects.requireNonNull(
                    observations.get(), "authority observation");
            if (!same(before, after) || !same(after, descriptor)) {
                return empty(true, Status.INCONSISTENT);
            }
            Status status = classify(after, worker, scheduler);
            return projected(after, descriptor, worker, scheduler, status);
        } catch (RuntimeException unavailable) {
            return unavailable();
        }
    }

    /**
     * Creates the state for a process without recovery-fleet composition.
     *
     * @return capability truth when no fleet composition is assembled
     */
    public static ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability disabled() {
        return empty(false, Status.DISABLED);
    }

    /**
     * Creates the state for a partial local recovery-fleet bean graph.
     *
     * @return fail-closed truth for a partial local bean graph
     */
    public static ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability incomplete() {
        return empty(true, Status.INCOMPLETE_COMPOSITION);
    }

    /**
     * Creates the state for a recovery-fleet bean graph with ambiguous candidates.
     *
     * @return fail-closed truth for multiple candidates at any composition seam
     */
    public static ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability ambiguous() {
        return empty(true, Status.AMBIGUOUS_COMPOSITION);
    }

    /**
     * Creates the state for an assembled fleet governed only by a local inventory.
     *
     * @return truth for an assembled local fleet without an external inventory authority
     */
    public static ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability unattested() {
        return empty(true, Status.UNATTESTED_INVENTORY);
    }

    /**
     * Creates the state for a local capability snapshot that cannot be read or validated.
     *
     * @return fail-closed truth when local snapshot projection throws
     */
    public static ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability unavailable() {
        return empty(true, Status.UNAVAILABLE);
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability projected(
            Observation observation,
            Descriptor descriptor,
            RuntimeSnapshot worker,
            Snapshot scheduler,
            Status status) {
        Object source = descriptor.properties().get("sourceType");
        String sourceType = source instanceof String value ? value : "";
        boolean dynamic = DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                .SOURCE_TYPE.equals(sourceType);
        boolean managedRoots = enabled(descriptor, "managedTrustRootRefresh");
        String rootStatus = text(descriptor, "managedTrustRootStatus", "DISABLED");
        long rootSequence = number(descriptor, "managedTrustRootSequence");
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability(
                SCHEMA_VERSION, true, status == Status.READY, status,
                descriptor.externallyAttested(), observation.available(), sourceType,
                observation.generation(), observation.laneCount(), dynamic,
                enabled(descriptor, "automaticRefresh"),
                enabled(descriptor, "signedRevocation"),
                enabled(descriptor, "witnessedPublications"),
                enabled(descriptor, "durableGenerationFloor"),
                enabled(descriptor, "externallyAnchoredPublicationFloor"),
                enabled(descriptor, "byzantineQuorumAnchoredPublicationFloor"),
                managedRoots, enabled(descriptor, "managedTrustRootAvailable"),
                rootStatus, rootSequence,
                enabled(descriptor, "atomicDualTrustRootPublication"),
                enabled(descriptor, "durableTrustRootFloor"),
                enabled(descriptor, "externallyAnchoredTrustRootFloor"),
                enabled(descriptor, "byzantineQuorumAnchoredTrustRootFloor"),
                enabled(descriptor, "externalInventoryNonEquivocation"),
                enabled(descriptor, "byzantineQuorumInventoryNonEquivocation"),
                scheduler.active(), scheduler.overdue(), scheduler.pollCount(),
                scheduler.pollFailureCount(), worker.cycleCount(), worker.cycleFailureCount());
    }

    private static Status classify(
            Observation observation,
            RuntimeSnapshot worker,
            Snapshot scheduler) {
        if (!observation.available()) {
            return Status.INVENTORY_UNAVAILABLE;
        }
        if (worker.closed() || scheduler.closed()) {
            return Status.RUNTIME_CLOSED;
        }
        if (scheduler.overdue()) {
            return Status.SCHEDULER_STALLED;
        }
        if (scheduler.lastPollFailed()) {
            return Status.SCHEDULER_FAILED;
        }
        if (worker.lastCycleFailed()) {
            return Status.CYCLE_FAILED;
        }
        if (scheduler.latestCycleHadLaneFailures()
                || worker.lastCompletedCycleHadLaneFailures()) {
            return Status.LANE_FAILURES;
        }
        return Status.READY;
    }

    private static boolean same(Observation left, Observation right) {
        return left.available() == right.available()
                && left.status().equals(right.status())
                && left.sourceType().equals(right.sourceType())
                && left.generation() == right.generation()
                && left.laneCount() == right.laneCount();
    }

    private static boolean same(Observation observation, Descriptor descriptor) {
        return observation.available() == descriptor.available()
                && observation.status().equals(descriptor.status())
                && observation.generation() == descriptor.generation()
                && observation.laneCount() == descriptor.laneCount()
                && observation.sourceType().equals(
                descriptor.properties().get("sourceType"));
    }

    private static boolean enabled(Descriptor descriptor, String property) {
        return Boolean.TRUE.equals(descriptor.properties().get(property));
    }

    private static String text(Descriptor descriptor, String property, String fallback) {
        Object value = descriptor.properties().get(property);
        return value instanceof String text ? text : fallback;
    }

    private static long number(Descriptor descriptor, String property) {
        Object value = descriptor.properties().get(property);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability empty(
            boolean configured,
            Status status) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability(
                SCHEMA_VERSION, configured, false, status, false, false, "", 0L, 0,
                false, false, false, false, false, false, false,
                false, false, "DISABLED", 0L, false, false, false, false,
                false, false, false, false,
                0L, 0L, 0L, 0L);
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    /** Closed, fixed-cardinality capability readiness reasons. */
    public enum Status {
        /** No recovery-fleet beans are assembled. */
        DISABLED,

        /** Some required composition beans are absent. */
        INCOMPLETE_COMPOSITION,

        /** More than one candidate exists at a required composition seam. */
        AMBIGUOUS_COMPOSITION,

        /** The local fleet uses a non-attested test inventory. */
        UNATTESTED_INVENTORY,

        /** Inventory, worker, and scheduler currently admit safe recovery polling. */
        READY,

        /** The external inventory is expired, revoked, stale, or refresh-failed. */
        INVENTORY_UNAVAILABLE,

        /** The worker or scheduler lifecycle has closed. */
        RUNTIME_CLOSED,

        /** The scheduler exceeded its bounded progress budget. */
        SCHEDULER_STALLED,

        /** The latest scheduler poll failed. */
        SCHEDULER_FAILED,

        /** The latest worker cycle failed a fleet-wide invariant. */
        CYCLE_FAILED,

        /** The latest completed cycle isolated at least one lane failure. */
        LANE_FAILURES,

        /** Local authority reads crossed generations or contradicted each other. */
        INCONSISTENT,

        /** A local snapshot could not be read or validated. */
        UNAVAILABLE
    }
}
