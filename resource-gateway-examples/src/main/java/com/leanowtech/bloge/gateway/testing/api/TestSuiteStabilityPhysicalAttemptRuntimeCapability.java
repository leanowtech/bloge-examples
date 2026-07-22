package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.actuate.health.Status;

import java.util.Map;
import java.util.Objects;

/**
 * Identity-free industrial-readiness projection for the physical-attempt runtime.
 *
 * <p>The projection brackets runtime and cohort reads with two inventory observations. A refresh
 * that tears the read is reported as {@link CapabilityStatus#INVENTORY_INCONSISTENT}. Readiness
 * requires a fresh externally attested dynamic inventory, automatic refresh, signed revocation,
 * witnessed publication ordering, a durable anti-rollback floor, externally durable Byzantine
 * non-equivocation, exact cross-replica generation convergence, and healthy
 * observation-reconciliation and terminal-projection runtimes.</p>
 *
 * <p>All reads are aggregate and must perform no provider, network, or payload operation.</p>
 *
 * @param schemaVersion capability protocol generation
 * @param configured whether any local physical-attempt capability composition is present
 * @param ready whether industrial physical-attempt execution may be advertised
 * @param status exact bounded readiness reason
 * @param providerInventory aggregate signed provider-inventory state
 * @param terminalProjectionReady whether durable terminal projection is locally healthy
 * @param observationReconciliationReady whether orphan observation reconciliation is healthy
 * @param dynamicInventory whether inventory can rotate without restart
 * @param automaticRefresh whether refresh is autonomous
 * @param signedRevocation whether signed inventory revocation is enforced
 * @param witnessedPublications whether publication ordering has an independent witness
 * @param durablePublicationFloor whether anti-rollback state survives restart
 * @param managedTrustRootRefresh whether both runtime verification-key domains rotate atomically
 * @param managedTrustRootAvailable whether the current managed dual-domain root is usable
 * @param managedTrustRootStatus bounded managed-root lifecycle state
 * @param managedTrustRootSequence current accepted root sequence, or zero when disabled
 * @param atomicDualTrustRootPublication whether one publication controls both key domains
 * @param durableTrustRootFloor whether root anti-rollback state survives restart
 * @param externallyAnchoredTrustRootFloor whether root ordering is committed outside this database
 * @param byzantineQuorumAnchoredTrustRootFloor whether the root anchor tolerates faulty notaries
 * @param cohortConverged whether every expected replica proves one exact generation
 * @param expectedReplicaCount attested cohort cardinality
 * @param readyReplicaCount replicas proving the exact current inventory generation
 */
public record TestSuiteStabilityPhysicalAttemptRuntimeCapability(
        String schemaVersion,
        boolean configured,
        boolean ready,
        CapabilityStatus status,
        TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Descriptor providerInventory,
        boolean terminalProjectionReady,
        boolean observationReconciliationReady,
        boolean dynamicInventory,
        boolean automaticRefresh,
        boolean signedRevocation,
        boolean witnessedPublications,
        boolean durablePublicationFloor,
        boolean managedTrustRootRefresh,
        boolean managedTrustRootAvailable,
        String managedTrustRootStatus,
        long managedTrustRootSequence,
        boolean atomicDualTrustRootPublication,
        boolean durableTrustRootFloor,
        boolean externallyAnchoredTrustRootFloor,
        boolean byzantineQuorumAnchoredTrustRootFloor,
        boolean cohortConverged,
        int expectedReplicaCount,
        int readyReplicaCount) {

    /** First physical-attempt runtime capability generation retained for negotiation. */
    public static final String SCHEMA_VERSION_V1 =
            "bloge.testSuiteStabilityPhysicalAttemptRuntimeCapability.v1";

    /** Current physical-attempt runtime capability generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityPhysicalAttemptRuntimeCapability.v2";

    /** Enforces that every readiness claim is justified by the projected aggregate facts. */
    public TestSuiteStabilityPhysicalAttemptRuntimeCapability {
        schemaVersion = Objects.requireNonNullElse(schemaVersion, "").trim();
        status = Objects.requireNonNull(status, "status");
        providerInventory = Objects.requireNonNull(providerInventory, "providerInventory");
        managedTrustRootStatus = Objects.requireNonNullElse(
                managedTrustRootStatus, "").trim();
        boolean disabled = status == CapabilityStatus.DISABLED;
        boolean completeReadyShape = configured && providerInventory.configured()
                && providerInventory.externallyAttested() && providerInventory.available()
                && terminalProjectionReady && observationReconciliationReady
                && dynamicInventory && automaticRefresh && signedRevocation
                && witnessedPublications && durablePublicationFloor
                && flag(providerInventory.properties(), "externalNonEquivocation")
                && flag(providerInventory.properties(), "byzantineQuorumNonEquivocation")
                && managedTrustRootRefresh && managedTrustRootAvailable
                && "HEALTHY".equals(managedTrustRootStatus)
                && managedTrustRootSequence > 0 && atomicDualTrustRootPublication
                && durableTrustRootFloor && externallyAnchoredTrustRootFloor
                && byzantineQuorumAnchoredTrustRootFloor
                && cohortConverged
                && expectedReplicaCount > 0 && readyReplicaCount == expectedReplicaCount;
        if (!SCHEMA_VERSION.equals(schemaVersion) || configured == disabled
                || ready != (status == CapabilityStatus.READY) || ready != completeReadyShape
                || expectedReplicaCount < 0 || expectedReplicaCount > 256
                || readyReplicaCount < 0 || readyReplicaCount > expectedReplicaCount
                || managedTrustRootStatus.isBlank() || managedTrustRootSequence < 0
                || dynamicInventory != flag(
                providerInventory.properties(), "dynamicInventory")
                || automaticRefresh != flag(
                providerInventory.properties(), "automaticRefresh")
                || signedRevocation != flag(
                providerInventory.properties(), "signedRevocation")
                || witnessedPublications != flag(
                providerInventory.properties(), "witnessedPublications")
                || durablePublicationFloor != flag(
                providerInventory.properties(), "durablePublicationFloor")
                || managedTrustRootRefresh != flag(
                providerInventory.properties(), "managedTrustRootRefresh")
                || managedTrustRootAvailable != flag(
                providerInventory.properties(), "managedTrustRootAvailable")
                || !managedTrustRootStatus.equals(text(
                providerInventory.properties(), "managedTrustRootStatus", "DISABLED"))
                || managedTrustRootSequence != nonNegativeLong(
                providerInventory.properties(), "managedTrustRootSequence")
                || atomicDualTrustRootPublication != flag(
                providerInventory.properties(), "atomicDualTrustRootPublication")
                || durableTrustRootFloor != flag(
                providerInventory.properties(), "durableTrustRootFloor")
                || externallyAnchoredTrustRootFloor != flag(
                providerInventory.properties(), "externallyAnchoredTrustRootFloor")
                || byzantineQuorumAnchoredTrustRootFloor != flag(
                providerInventory.properties(), "byzantineQuorumAnchoredTrustRootFloor")
                || !configured && (providerInventory.configured()
                || terminalProjectionReady || observationReconciliationReady
                || dynamicInventory || automaticRefresh || signedRevocation
                || witnessedPublications || durablePublicationFloor
                || managedTrustRootRefresh || managedTrustRootAvailable
                || managedTrustRootSequence != 0 || atomicDualTrustRootPublication
                || durableTrustRootFloor || externallyAnchoredTrustRootFloor
                || byzantineQuorumAnchoredTrustRootFloor || cohortConverged
                || expectedReplicaCount != 0 || readyReplicaCount != 0)
                || cohortConverged && (expectedReplicaCount < 1
                || readyReplicaCount != expectedReplicaCount)
                || automaticRefresh && !dynamicInventory
                || flag(providerInventory.properties(), "byzantineQuorumNonEquivocation")
                && !flag(providerInventory.properties(), "externalNonEquivocation")
                || managedTrustRootAvailable && (!managedTrustRootRefresh
                || !"HEALTHY".equals(managedTrustRootStatus)
                || managedTrustRootSequence < 1)
                || !managedTrustRootRefresh && (!"DISABLED".equals(managedTrustRootStatus)
                || managedTrustRootSequence != 0 || atomicDualTrustRootPublication
                || durableTrustRootFloor || externallyAnchoredTrustRootFloor
                || byzantineQuorumAnchoredTrustRootFloor)
                || atomicDualTrustRootPublication && !managedTrustRootRefresh
                || durableTrustRootFloor && !managedTrustRootRefresh
                || externallyAnchoredTrustRootFloor && !durableTrustRootFloor
                || byzantineQuorumAnchoredTrustRootFloor
                && !externallyAnchoredTrustRootFloor
                || (signedRevocation || witnessedPublications || durablePublicationFloor)
                && !dynamicInventory) {
            throw new IllegalArgumentException(
                    "Physical-attempt runtime capability is invalid");
        }
    }

    /**
     * Projects one exact local composition with generation-tear detection.
     *
     * @param inventory signed provider-inventory resolver
     * @param cohort durable cross-replica inventory convergence gate
     * @param reconciliation observation-reconciliation readiness
     * @param terminalProjection terminal-projection readiness
     * @return current identity-free capability
     */
    public static TestSuiteStabilityPhysicalAttemptRuntimeCapability project(
            TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority inventory,
            TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate cohort,
            TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth reconciliation,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth terminalProjection) {
        try {
            TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority requiredInventory =
                    Objects.requireNonNull(inventory, "inventory");
            var first = requiredInventory.observation();
            var descriptor = requiredInventory.descriptor();
            boolean reconciliationReady = Status.UP.equals(Objects.requireNonNull(
                    reconciliation, "reconciliation").health().getStatus());
            boolean terminalReady = Status.UP.equals(Objects.requireNonNull(
                    terminalProjection, "terminalProjection").health().getStatus());
            var cohortObservation = Objects.requireNonNull(cohort, "cohort").observation();
            var last = requiredInventory.observation();
            boolean dynamic = flag(descriptor.properties(), "dynamicInventory");
            boolean refresh = flag(descriptor.properties(), "automaticRefresh");
            boolean revocation = flag(descriptor.properties(), "signedRevocation");
            boolean witnessed = flag(descriptor.properties(), "witnessedPublications");
            boolean durableFloor = flag(descriptor.properties(), "durablePublicationFloor");
            boolean external = flag(descriptor.properties(), "externalNonEquivocation");
            boolean byzantine = flag(
                    descriptor.properties(), "byzantineQuorumNonEquivocation");
            boolean managedRootRefresh = flag(
                    descriptor.properties(), "managedTrustRootRefresh");
            boolean managedRootAvailable = flag(
                    descriptor.properties(), "managedTrustRootAvailable");
            String managedRootStatus = text(
                    descriptor.properties(), "managedTrustRootStatus", "DISABLED");
            long managedRootSequence = nonNegativeLong(
                    descriptor.properties(), "managedTrustRootSequence");
            boolean atomicDualRootPublication = flag(
                    descriptor.properties(), "atomicDualTrustRootPublication");
            boolean durableRootFloor = flag(
                    descriptor.properties(), "durableTrustRootFloor");
            boolean externalRootFloor = flag(
                    descriptor.properties(), "externallyAnchoredTrustRootFloor");
            boolean byzantineRootFloor = flag(
                    descriptor.properties(), "byzantineQuorumAnchoredTrustRootFloor");
            boolean stableInventory = first.equals(last)
                    && descriptor.revision() == first.revision();
            boolean exactCohort = stableInventory && cohortObservation.available()
                    && cohortObservation.inventorySourceSequence() == first.sourceSequence()
                    && cohortObservation.inventoryGenerationFingerprint().equals(
                    first.sourceGenerationFingerprint());
            boolean converged = exactCohort
                    && cohortObservation.readyReplicas()
                    == cohortObservation.expectedReplicas()
                    && cohortObservation.distinctInventoryGenerations() == 1;
            CapabilityStatus status = classify(
                    first.available(), stableInventory, dynamic, refresh, revocation, witnessed,
                    durableFloor, external, byzantine, managedRootRefresh,
                    managedRootAvailable, atomicDualRootPublication, durableRootFloor,
                    externalRootFloor, byzantineRootFloor, exactCohort, converged,
                    reconciliationReady && terminalReady);
            return new TestSuiteStabilityPhysicalAttemptRuntimeCapability(
                    SCHEMA_VERSION, true, status == CapabilityStatus.READY, status, descriptor,
                    terminalReady, reconciliationReady, dynamic, refresh, revocation, witnessed,
                    durableFloor, managedRootRefresh, managedRootAvailable,
                    managedRootStatus, managedRootSequence, atomicDualRootPublication,
                    durableRootFloor, externalRootFloor, byzantineRootFloor,
                    converged, cohortObservation.expectedReplicas(),
                    cohortObservation.readyReplicas());
        } catch (RuntimeException unavailable) {
            return unavailable();
        }
    }

    /**
     * Returns a physically absent capability state.
     *
     * @return disabled capability
     */
    public static TestSuiteStabilityPhysicalAttemptRuntimeCapability disabled() {
        return closed(false, CapabilityStatus.DISABLED);
    }

    /**
     * Returns a partially assembled capability state.
     *
     * @return incomplete capability
     */
    public static TestSuiteStabilityPhysicalAttemptRuntimeCapability incomplete() {
        return closed(true, CapabilityStatus.INCOMPLETE);
    }

    /**
     * Returns an ambiguous multi-bean capability state.
     *
     * @return ambiguous capability
     */
    public static TestSuiteStabilityPhysicalAttemptRuntimeCapability ambiguous() {
        return closed(true, CapabilityStatus.AMBIGUOUS);
    }

    /**
     * Returns a capability whose aggregate sources could not be read safely.
     *
     * @return unavailable capability
     */
    public static TestSuiteStabilityPhysicalAttemptRuntimeCapability unavailable() {
        return closed(true, CapabilityStatus.UNAVAILABLE);
    }

    private static TestSuiteStabilityPhysicalAttemptRuntimeCapability closed(
            boolean configured, CapabilityStatus status) {
        return new TestSuiteStabilityPhysicalAttemptRuntimeCapability(
                SCHEMA_VERSION, configured, false, status,
                TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Descriptor.disabled(),
                false, false, false, false, false, false, false,
                false, false, "DISABLED", 0, false, false, false, false,
                false, 0, 0);
    }

    private static CapabilityStatus classify(
            boolean inventoryAvailable,
            boolean stableInventory,
            boolean dynamic,
            boolean refresh,
            boolean revocation,
            boolean witnessed,
            boolean durableFloor,
            boolean external,
            boolean byzantine,
            boolean managedRootRefresh,
            boolean managedRootAvailable,
            boolean atomicDualRootPublication,
            boolean durableRootFloor,
            boolean externalRootFloor,
            boolean byzantineRootFloor,
            boolean exactCohort,
            boolean converged,
            boolean runtimesReady) {
        if (!inventoryAvailable) {
            return CapabilityStatus.INVENTORY_UNAVAILABLE;
        }
        if (!stableInventory) {
            return CapabilityStatus.INVENTORY_INCONSISTENT;
        }
        if (!dynamic) {
            return CapabilityStatus.DYNAMIC_INVENTORY_REQUIRED;
        }
        if (!refresh) {
            return CapabilityStatus.AUTOMATIC_REFRESH_REQUIRED;
        }
        if (!revocation) {
            return CapabilityStatus.SIGNED_REVOCATION_REQUIRED;
        }
        if (!witnessed) {
            return CapabilityStatus.WITNESS_REQUIRED;
        }
        if (!durableFloor) {
            return CapabilityStatus.DURABLE_FLOOR_REQUIRED;
        }
        if (!external) {
            return CapabilityStatus.EXTERNAL_ANCHOR_REQUIRED;
        }
        if (!byzantine) {
            return CapabilityStatus.BYZANTINE_QUORUM_REQUIRED;
        }
        if (!managedRootRefresh || !atomicDualRootPublication) {
            return CapabilityStatus.MANAGED_TRUST_ROOT_REQUIRED;
        }
        if (!managedRootAvailable) {
            return CapabilityStatus.MANAGED_TRUST_ROOT_UNAVAILABLE;
        }
        if (!durableRootFloor) {
            return CapabilityStatus.DURABLE_TRUST_ROOT_FLOOR_REQUIRED;
        }
        if (!externalRootFloor) {
            return CapabilityStatus.EXTERNAL_TRUST_ROOT_ANCHOR_REQUIRED;
        }
        if (!byzantineRootFloor) {
            return CapabilityStatus.BYZANTINE_TRUST_ROOT_QUORUM_REQUIRED;
        }
        if (!exactCohort) {
            return CapabilityStatus.COHORT_UNAVAILABLE;
        }
        if (!converged) {
            return CapabilityStatus.COHORT_NOT_CONVERGED;
        }
        return runtimesReady ? CapabilityStatus.READY : CapabilityStatus.RUNTIME_UNAVAILABLE;
    }

    private static boolean flag(Map<String, Object> properties, String name) {
        return Boolean.TRUE.equals(properties.get(name));
    }

    private static String text(
            Map<String, Object> properties, String name, String defaultValue) {
        return properties.get(name) instanceof String value ? value : defaultValue;
    }

    private static long nonNegativeLong(Map<String, Object> properties, String name) {
        return properties.get(name) instanceof Number value
                ? Math.max(0L, value.longValue()) : 0L;
    }

    /** Closed machine-readable physical-attempt readiness reason. */
    public enum CapabilityStatus {
        /** No local physical-attempt runtime composition is present. */
        DISABLED,
        /** Required local runtime or convergence sources are missing. */
        INCOMPLETE,
        /** More than one candidate exists for a singleton composition role. */
        AMBIGUOUS,
        /** The current signed inventory is expired, revoked, or otherwise unavailable. */
        INVENTORY_UNAVAILABLE,
        /** Aggregate reads crossed an inventory refresh generation. */
        INVENTORY_INCONSISTENT,
        /** Restart-free signed inventory rotation is not implemented. */
        DYNAMIC_INVENTORY_REQUIRED,
        /** Dynamic inventory does not refresh autonomously. */
        AUTOMATIC_REFRESH_REQUIRED,
        /** Signed revocation is not enforced. */
        SIGNED_REVOCATION_REQUIRED,
        /** Publication ordering lacks an independent witness. */
        WITNESS_REQUIRED,
        /** Publication rollback state does not survive restart. */
        DURABLE_FLOOR_REQUIRED,
        /** Publication history is not committed outside the Resource Gateway database. */
        EXTERNAL_ANCHOR_REQUIRED,
        /** External ordering does not tolerate a non-zero Byzantine fault bound. */
        BYZANTINE_QUORUM_REQUIRED,
        /** Runtime verification keys are static or are not published as one dual-domain unit. */
        MANAGED_TRUST_ROOT_REQUIRED,
        /** The configured managed dual-domain verification-key set is not currently usable. */
        MANAGED_TRUST_ROOT_UNAVAILABLE,
        /** Managed-root rollback state does not survive restart. */
        DURABLE_TRUST_ROOT_FLOOR_REQUIRED,
        /** Managed-root history is not committed outside the Resource Gateway database. */
        EXTERNAL_TRUST_ROOT_ANCHOR_REQUIRED,
        /** Managed-root external ordering does not tolerate a faulty notary. */
        BYZANTINE_TRUST_ROOT_QUORUM_REQUIRED,
        /** Cohort state is absent, stale, or for another inventory generation. */
        COHORT_UNAVAILABLE,
        /** Expected replicas do not all prove one exact inventory generation. */
        COHORT_NOT_CONVERGED,
        /** Observation reconciliation or terminal projection is unhealthy. */
        RUNTIME_UNAVAILABLE,
        /** Every inventory, convergence, and runtime invariant is currently satisfied. */
        READY,
        /** One or more aggregate sources failed closed while being read. */
        UNAVAILABLE
    }
}
