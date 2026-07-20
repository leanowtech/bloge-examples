package com.leanowtech.bloge.gateway.testing.api;

/** Fail-closed local projection of database-backed test-secret trust convergence. */
@FunctionalInterface
public interface TestSecretAuthorityTrustCohortGate {

    /** @return current aggregate-only readiness without remote authority or JWKS I/O */
    Descriptor descriptor();

    /** @return disabled gate preserving static and single-process backwards-compatible behavior */
    static TestSecretAuthorityTrustCohortGate localOnly() {
        return Descriptor::localOnly;
    }

    /**
     * Aggregate capability and resolution-gate facts without member ids or trust fingerprints.
     *
     * @param schemaVersion descriptor protocol generation
     * @param configured whether exact cohort gating is enabled
     * @param available whether secret resolution may currently proceed
     * @param status primary closed readiness state
     * @param expectedReplicaCount configured exact inventory cardinality
     * @param liveReplicaCount current database-live process count
     * @param healthyReplicaCount live locally healthy process count
     * @param distinctTrustGenerationCount distinct complete trust generations
     * @param leaseDurationSeconds database liveness lease
     * @param databaseAuthority whether liveness uses database time
     * @param exactConfiguredInventory whether missing, duplicate and unexpected members block
     */
    record Descriptor(
            String schemaVersion,
            boolean configured,
            boolean available,
            String status,
            int expectedReplicaCount,
            int liveReplicaCount,
            int healthyReplicaCount,
            int distinctTrustGenerationCount,
            long leaseDurationSeconds,
            boolean databaseAuthority,
            boolean exactConfiguredInventory) {

        /** Current aggregate cohort descriptor protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.testSecretAuthorityTrustCohortDescriptor.v1";

        /** Validates the bounded payload-free gate projection. */
        public Descriptor {
            schemaVersion = normalized(schemaVersion);
            status = normalized(status);
            int maximum = TestSuiteStabilityAuthorityCohortPolicy.maximumReplicas() * 2;
            if (!SCHEMA_VERSION.equals(schemaVersion) || status.isBlank()
                    || expectedReplicaCount < 0
                    || expectedReplicaCount
                    > TestSuiteStabilityAuthorityCohortPolicy.maximumReplicas()
                    || liveReplicaCount < 0 || liveReplicaCount > maximum
                    || healthyReplicaCount < 0 || healthyReplicaCount > liveReplicaCount
                    || distinctTrustGenerationCount < 0
                    || distinctTrustGenerationCount > liveReplicaCount
                    || leaseDurationSeconds < 0 || leaseDurationSeconds > 900
                    || available && configured && (!"CONVERGED".equals(status)
                    || expectedReplicaCount == 0
                    || liveReplicaCount != expectedReplicaCount
                    || healthyReplicaCount != expectedReplicaCount
                    || distinctTrustGenerationCount != 1
                    || !databaseAuthority || !exactConfiguredInventory)
                    || !configured && (!available || !"LOCAL_ONLY".equals(status)
                    || expectedReplicaCount != 0 || liveReplicaCount != 0
                    || healthyReplicaCount != 0 || distinctTrustGenerationCount != 0
                    || leaseDurationSeconds != 0 || databaseAuthority
                    || exactConfiguredInventory)) {
                throw new IllegalArgumentException(
                        "Invalid test-secret authority trust cohort descriptor");
            }
        }

        /** @return disabled non-network local gate */
        public static Descriptor localOnly() {
            return new Descriptor(SCHEMA_VERSION, false, true, "LOCAL_ONLY",
                    0, 0, 0, 0, 0, false, false);
        }

        /** @return configured fail-closed descriptor when database state cannot be read */
        public static Descriptor unavailable(int expectedReplicaCount, long leaseSeconds) {
            return new Descriptor(SCHEMA_VERSION, true, false, "STORE_UNAVAILABLE",
                    expectedReplicaCount, 0, 0, 0, leaseSeconds, true, true);
        }

        private static String normalized(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
