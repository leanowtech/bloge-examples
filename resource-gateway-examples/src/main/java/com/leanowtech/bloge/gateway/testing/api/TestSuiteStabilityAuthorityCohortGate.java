package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;

/** Fail-closed local projection of database-backed authority-trust cohort convergence. */
@FunctionalInterface
public interface TestSuiteStabilityAuthorityCohortGate {

    /** @return current aggregate-only cohort readiness; implementations must not call the PDP */
    Descriptor descriptor();

    /** @return a disabled gate that preserves local-only backwards-compatible behavior */
    static TestSuiteStabilityAuthorityCohortGate localOnly() {
        return () -> Descriptor.localOnly();
    }

    /**
     * Aggregate capability and worker-gate facts without instance ids or trust fingerprints.
     *
     * @param schemaVersion descriptor protocol generation
     * @param configured whether exact cohort gating is enabled
     * @param available whether current admission and worker claim are allowed
     * @param status primary closed readiness state
     * @param expectedReplicaCount configured exact inventory cardinality
     * @param liveReplicaCount current database-live process count
     * @param healthyReplicaCount live locally healthy process count
     * @param distinctSnapshotCount distinct trust-generation count
     * @param distinctServingInventoryGenerationCount distinct inventory publication generations
     * @param leaseDurationSeconds database liveness lease
     * @param databaseAuthority whether liveness uses database time
     * @param exactConfiguredInventory whether unexpected or missing members block readiness
     * @param externallyAttestedInventory whether independent signatures establish expected members
     * @param dynamicallyRefreshedInventory whether signed freshness/revocation is remotely refreshed
     * @param witnessedInventoryPublications whether an independent signer witnesses publication order
     * @param durableInventoryPublicationFloor whether publication order survives fleet restart
     * @param managedInventoryTrustRoots whether runtime inventory keys refresh without restart
     * @param atomicDualInventoryTrustRootPublication whether both runtime key sets share one signed generation
     */
    record Descriptor(
            String schemaVersion,
            boolean configured,
            boolean available,
            String status,
            int expectedReplicaCount,
            int liveReplicaCount,
            int healthyReplicaCount,
            int distinctSnapshotCount,
            int distinctServingInventoryGenerationCount,
            long leaseDurationSeconds,
            boolean databaseAuthority,
            boolean exactConfiguredInventory,
            boolean externallyAttestedInventory,
            boolean dynamicallyRefreshedInventory,
            boolean witnessedInventoryPublications,
            boolean durableInventoryPublicationFloor,
            boolean managedInventoryTrustRoots,
            boolean atomicDualInventoryTrustRootPublication) {

        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityAuthorityCohortDescriptor.v3";

        /** Validates the bounded payload-free descriptor. */
        public Descriptor {
            schemaVersion = normalized(schemaVersion);
            status = normalized(status);
            int maximum = TestSuiteStabilityAuthorityCohortPolicy.maximumReplicas() * 2;
            if (!SCHEMA_VERSION.equals(schemaVersion) || status.isBlank()
                    || expectedReplicaCount < 0
                    || expectedReplicaCount > TestSuiteStabilityAuthorityCohortPolicy
                    .maximumReplicas()
                    || liveReplicaCount < 0 || liveReplicaCount > maximum
                    || healthyReplicaCount < 0 || healthyReplicaCount > liveReplicaCount
                    || distinctSnapshotCount < 0 || distinctSnapshotCount > liveReplicaCount
                    || distinctServingInventoryGenerationCount < 0
                    || distinctServingInventoryGenerationCount > liveReplicaCount
                    || leaseDurationSeconds < 0 || leaseDurationSeconds > 900
                    || available && configured && (!"CONVERGED".equals(status)
                    || expectedReplicaCount == 0
                    || liveReplicaCount != expectedReplicaCount
                    || healthyReplicaCount != expectedReplicaCount
                    || distinctSnapshotCount != 1
                    || externallyAttestedInventory
                    && distinctServingInventoryGenerationCount != 1
                    || !databaseAuthority || !exactConfiguredInventory)
                    || dynamicallyRefreshedInventory && !externallyAttestedInventory
                    || witnessedInventoryPublications && !dynamicallyRefreshedInventory
                    || durableInventoryPublicationFloor && !witnessedInventoryPublications
                    || dynamicallyRefreshedInventory && !durableInventoryPublicationFloor
                    || managedInventoryTrustRoots && !dynamicallyRefreshedInventory
                    || atomicDualInventoryTrustRootPublication && !managedInventoryTrustRoots
                    || managedInventoryTrustRoots && !atomicDualInventoryTrustRootPublication
                    || !configured && (!available || !"LOCAL_ONLY".equals(status))) {
                throw new IllegalArgumentException(
                        "Invalid stability authority cohort descriptor");
            }
        }

        /** @return disabled non-network local gate */
        public static Descriptor localOnly() {
            return new Descriptor(SCHEMA_VERSION, false, true, "LOCAL_ONLY",
                    0, 0, 0, 0, 0, 0, false, false, false, false, false, false,
                    false, false);
        }

        /** @return configured fail-closed descriptor when the store cannot be read */
        public static Descriptor unavailable(
                int expectedReplicaCount,
                long leaseSeconds,
                boolean externallyAttestedInventory) {
            return unavailable(expectedReplicaCount, leaseSeconds,
                    externallyAttestedInventory, false, false, false, false);
        }

        /** @return configured fail-closed descriptor preserving source semantics */
        public static Descriptor unavailable(
                int expectedReplicaCount,
                long leaseSeconds,
                boolean externallyAttestedInventory,
                boolean dynamicallyRefreshedInventory,
                boolean witnessedInventoryPublications) {
            return new Descriptor(SCHEMA_VERSION, true, false, "STORE_UNAVAILABLE",
                    expectedReplicaCount, 0, 0, 0, 0, leaseSeconds, true, true,
                    externallyAttestedInventory, dynamicallyRefreshedInventory,
                    witnessedInventoryPublications, dynamicallyRefreshedInventory,
                    false, false);
        }

        /** @return configured fail-closed descriptor preserving managed-root semantics */
        public static Descriptor unavailable(
                int expectedReplicaCount,
                long leaseSeconds,
                boolean externallyAttestedInventory,
                boolean dynamicallyRefreshedInventory,
                boolean witnessedInventoryPublications,
                boolean managedInventoryTrustRoots,
                boolean atomicDualInventoryTrustRootPublication) {
            return new Descriptor(SCHEMA_VERSION, true, false, "STORE_UNAVAILABLE",
                    expectedReplicaCount, 0, 0, 0, 0, leaseSeconds, true, true,
                    externallyAttestedInventory, dynamicallyRefreshedInventory,
                    witnessedInventoryPublications, dynamicallyRefreshedInventory,
                    managedInventoryTrustRoots, atomicDualInventoryTrustRootPublication);
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
