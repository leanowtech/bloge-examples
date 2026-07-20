package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.List;

/** Database-clock authority for exact test-secret trust-cohort membership. */
public interface TestSecretAuthorityTrustCohortRepository {

    /** Publishes the current local trust generation and returns one coherent cohort snapshot. */
    Snapshot heartbeat(DynamicJwksTestSecretAuthorityTrustStore.CohortObservation observation);

    /** Reads one bounded coherent cohort snapshot without mutating membership. */
    Snapshot snapshot();

    /** Withdraws only this exact process-start row; failed withdrawal remains lease-bounded. */
    void withdraw(String instanceId, String startupId);

    /**
     * Aggregate-only database-authoritative cohort state.
     *
     * @param schemaVersion snapshot protocol generation
     * @param converged whether every configured slot has one live equivalent healthy process
     * @param status primary closed status
     * @param expectedReplicaCount configured exact inventory cardinality
     * @param liveReplicaCount unexpired process-start rows
     * @param healthyReplicaCount live rows satisfying local trust readiness
     * @param distinctTrustGenerationCount distinct complete trust-generation fingerprints
     * @param missingReplicaCount configured slots with no live process
     * @param unexpectedReplicaCount live slots absent from configured inventory
     * @param duplicateReplicaCount configured slots with multiple live process starts
     * @param divergentArtifactCount live rows with an unexpected immutable artifact
     * @param divergentPolicyCount live rows with an unexpected exact cohort policy
     * @param divergentProtocolCount live rows with an unexpected protocol generation
     * @param divergentAuthorityCount live rows with an unexpected authority/provider identity
     * @param observedAt database observation time
     * @param nextLeaseExpiryAt earliest live membership expiry, possibly null
     * @param blockers bounded closed blocker-code set
     */
    record Snapshot(
            String schemaVersion,
            boolean converged,
            String status,
            int expectedReplicaCount,
            int liveReplicaCount,
            int healthyReplicaCount,
            int distinctTrustGenerationCount,
            int missingReplicaCount,
            int unexpectedReplicaCount,
            int duplicateReplicaCount,
            int divergentArtifactCount,
            int divergentPolicyCount,
            int divergentProtocolCount,
            int divergentAuthorityCount,
            Instant observedAt,
            Instant nextLeaseExpiryAt,
            List<String> blockers) {

        /** Current aggregate snapshot protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.testSecretAuthorityTrustCohortSnapshot.v1";

        /** Validates bounded aggregate facts without admitting member identities or fingerprints. */
        public Snapshot {
            schemaVersion = normalized(schemaVersion);
            status = normalized(status);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            int maximum = TestSuiteStabilityAuthorityCohortPolicy.maximumReplicas() * 2;
            boolean countsValid = expectedReplicaCount > 0
                    && expectedReplicaCount
                    <= TestSuiteStabilityAuthorityCohortPolicy.maximumReplicas()
                    && liveReplicaCount >= 0 && liveReplicaCount <= maximum
                    && healthyReplicaCount >= 0 && healthyReplicaCount <= liveReplicaCount
                    && distinctTrustGenerationCount >= 0
                    && distinctTrustGenerationCount <= liveReplicaCount
                    && missingReplicaCount >= 0 && missingReplicaCount <= expectedReplicaCount
                    && unexpectedReplicaCount >= 0
                    && unexpectedReplicaCount <= liveReplicaCount
                    && duplicateReplicaCount >= 0
                    && duplicateReplicaCount <= expectedReplicaCount
                    && divergentArtifactCount >= 0
                    && divergentArtifactCount <= liveReplicaCount
                    && divergentPolicyCount >= 0
                    && divergentPolicyCount <= liveReplicaCount
                    && divergentProtocolCount >= 0
                    && divergentProtocolCount <= liveReplicaCount
                    && divergentAuthorityCount >= 0
                    && divergentAuthorityCount <= liveReplicaCount;
            if (!SCHEMA_VERSION.equals(schemaVersion) || status.isBlank() || !countsValid
                    || observedAt == null || blockers.size() > 16
                    || blockers.stream().anyMatch(value -> value == null
                    || !value.matches("[A-Z][A-Z0-9_]{0,63}"))
                    || converged != blockers.isEmpty()
                    || converged && (!"CONVERGED".equals(status)
                    || liveReplicaCount != expectedReplicaCount
                    || healthyReplicaCount != expectedReplicaCount
                    || distinctTrustGenerationCount != 1)) {
                throw new IllegalArgumentException(
                        "Invalid test-secret authority trust cohort snapshot");
            }
        }

        private static String normalized(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
