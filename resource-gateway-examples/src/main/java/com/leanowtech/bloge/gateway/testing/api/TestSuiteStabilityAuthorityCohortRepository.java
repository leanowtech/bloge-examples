package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Database-clock authority for exact suite-stability trust-cohort membership. */
public interface TestSuiteStabilityAuthorityCohortRepository {

    /** Publishes or replaces this exact process-start heartbeat and returns a coherent snapshot. */
    Snapshot heartbeat(Member member);

    /** Reads one bounded coherent cohort snapshot without mutating membership. */
    Snapshot snapshot();

    /** Withdraws only this exact process-start row; a failed withdrawal remains lease-bounded. */
    void withdraw(String instanceId, String startupId);

    /**
     * One payload-free private membership heartbeat.
     *
     * @param schemaVersion heartbeat protocol generation
     * @param scopeId stable serving-fleet scope
     * @param cohortId deployment generation
     * @param instanceId stable serving slot
     * @param startupId unique process-start UUID
     * @param artifactFingerprint immutable binary/image identity
     * @param policyFingerprint exact shared cohort policy identity
     * @param protocolVersion Resource Gateway integration protocol generation
     * @param authorityId signed-decision authority identity
     * @param providerType local trust provider type
     * @param trustAvailable local hard-fence readiness
     * @param refreshState local refresh state
     * @param snapshotFingerprint complete public trust-generation identity
     * @param servingInventorySourceSequence signed inventory publication generation
     * @param servingInventoryGenerationFingerprint private publication/witness identity
     * @param activeKeyCount active local verification-key count
     * @param lastSuccessfulRefreshAt last complete local refresh publication
     */
    record Member(
            String schemaVersion,
            String scopeId,
            String cohortId,
            String instanceId,
            String startupId,
            String artifactFingerprint,
            String policyFingerprint,
            String protocolVersion,
            String authorityId,
            String providerType,
            boolean trustAvailable,
            String refreshState,
            String snapshotFingerprint,
            long servingInventorySourceSequence,
            String servingInventoryGenerationFingerprint,
            long activeKeyCount,
            Instant lastSuccessfulRefreshAt) {

        /** Current persisted heartbeat generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityAuthorityCohortMember.v2";

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Validates the bounded non-payload observation before any database mutation. */
        public Member {
            schemaVersion = normalized(schemaVersion);
            scopeId = normalized(scopeId);
            cohortId = normalized(cohortId);
            instanceId = normalized(instanceId);
            startupId = normalized(startupId);
            artifactFingerprint = normalized(artifactFingerprint);
            policyFingerprint = normalized(policyFingerprint);
            protocolVersion = normalized(protocolVersion);
            authorityId = normalized(authorityId);
            providerType = normalized(providerType);
            refreshState = normalized(refreshState);
            snapshotFingerprint = normalized(snapshotFingerprint);
            servingInventoryGenerationFingerprint = normalized(
                    servingInventoryGenerationFingerprint);
            boolean inventoryGenerationValid = servingInventorySourceSequence == 0
                    && servingInventoryGenerationFingerprint.isEmpty()
                    || servingInventorySourceSequence > 0
                    && FINGERPRINT.matcher(
                    servingInventoryGenerationFingerprint).matches();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(scopeId).matches()
                    || !IDENTIFIER.matcher(cohortId).matches()
                    || !IDENTIFIER.matcher(instanceId).matches()
                    || !validUuid(startupId)
                    || !FINGERPRINT.matcher(artifactFingerprint).matches()
                    || !FINGERPRINT.matcher(policyFingerprint).matches()
                    || !IDENTIFIER.matcher(protocolVersion).matches()
                    || !IDENTIFIER.matcher(authorityId).matches()
                    || !"DYNAMIC_JWKS_ED25519".equals(providerType)
                    || refreshState.isBlank()
                    || !inventoryGenerationValid
                    || activeKeyCount < 0 || activeKeyCount > 64
                    || trustAvailable && (!"HEALTHY".equals(refreshState)
                    || !FINGERPRINT.matcher(snapshotFingerprint).matches()
                    || activeKeyCount == 0 || lastSuccessfulRefreshAt == null)) {
                throw new IllegalArgumentException(
                        "Invalid stability authority cohort member");
            }
        }
    }

    /**
     * Aggregate-only database-authoritative cohort state.
     *
     * @param schemaVersion snapshot protocol generation
     * @param converged whether every configured slot has one live equivalent healthy process
     * @param status primary closed status
     * @param expectedReplicaCount configured exact inventory cardinality
     * @param liveReplicaCount unexpired process-start rows
     * @param healthyReplicaCount live rows satisfying local trust readiness
     * @param distinctSnapshotCount distinct live trust-generation fingerprints
     * @param distinctServingInventoryGenerationCount distinct signed publication generations
     * @param missingReplicaCount configured slots with no live process
     * @param unexpectedReplicaCount live slots absent from configured inventory
     * @param duplicateReplicaCount configured slots with multiple live process starts
     * @param divergentArtifactCount live rows with an unexpected immutable artifact
     * @param divergentPolicyCount live rows with an unexpected exact cohort policy
     * @param divergentProtocolCount live rows with an unexpected protocol generation
     * @param divergentAuthorityCount live rows with an unexpected authority/provider identity
     * @param observedAt database observation time
     * @param nextLeaseExpiryAt earliest live membership expiry, possibly null
     * @param blockers bounded closed blocker set
     */
    record Snapshot(
            String schemaVersion,
            boolean converged,
            String status,
            int expectedReplicaCount,
            int liveReplicaCount,
            int healthyReplicaCount,
            int distinctSnapshotCount,
            int distinctServingInventoryGenerationCount,
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

        /** Validates the aggregate-only bounded snapshot. */
        public Snapshot {
            schemaVersion = normalized(schemaVersion);
            status = normalized(status);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            int maximumRows = TestSuiteStabilityAuthorityCohortPolicy.maximumReplicas() * 2;
            boolean countsValid = expectedReplicaCount > 0
                    && expectedReplicaCount <= TestSuiteStabilityAuthorityCohortPolicy
                    .maximumReplicas()
                    && liveReplicaCount >= 0 && liveReplicaCount <= maximumRows
                    && healthyReplicaCount >= 0 && healthyReplicaCount <= liveReplicaCount
                    && distinctSnapshotCount >= 0 && distinctSnapshotCount <= liveReplicaCount
                    && distinctServingInventoryGenerationCount >= 0
                    && distinctServingInventoryGenerationCount <= liveReplicaCount
                    && missingReplicaCount >= 0 && missingReplicaCount <= expectedReplicaCount
                    && unexpectedReplicaCount >= 0 && unexpectedReplicaCount <= liveReplicaCount
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
            if (!"bloge.testSuiteStabilityAuthorityCohortSnapshot.v1".equals(schemaVersion)
                    || status.isBlank() || !countsValid || observedAt == null
                    || blockers.size() > 16 || blockers.stream().anyMatch(
                    value -> value == null || !value.matches("[A-Z][A-Z0-9_]{0,63}"))
                    || converged != blockers.isEmpty()
                    || converged && (!"CONVERGED".equals(status)
                    || liveReplicaCount != expectedReplicaCount
                    || healthyReplicaCount != expectedReplicaCount
                    || distinctSnapshotCount != 1)) {
                throw new IllegalArgumentException(
                        "Invalid stability authority cohort snapshot");
            }
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean validUuid(String value) {
        try {
            return java.util.UUID.fromString(value).toString().equals(value);
        } catch (RuntimeException invalid) {
            return false;
        }
    }
}
