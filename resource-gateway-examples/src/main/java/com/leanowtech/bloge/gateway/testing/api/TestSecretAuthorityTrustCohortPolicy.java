package com.leanowtech.bloge.gateway.testing.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Exact deployment-owned membership policy for test-secret authority trust convergence.
 *
 * <p>The configured member set is an authority, not discovery output. Every process in one
 * deployment generation must receive the same fleet scope, cohort id, immutable artifact,
 * authority identity, protocol generation and complete serving-slot set. Missing, unexpected or
 * duplicate live processes therefore close secret resolution instead of silently changing the
 * quorum being evaluated.</p>
 *
 * @param scopeId stable serving-fleet scope across deployment generations
 * @param cohortId immutable deployment generation identity
 * @param instanceId stable serving slot represented by this process
 * @param startupId unique UUID for this process start
 * @param artifactFingerprint exact immutable image or application SHA-256
 * @param expectedInstanceIds complete configured serving-slot inventory
 * @param authorityId exact signed test-secret authority identity
 * @param protocolVersion exact signed-response protocol generation
 * @param heartbeatInterval local publication interval
 * @param leaseDuration database-clock liveness window, at least three heartbeat intervals
 * @param recordRetention expired membership retention before bounded deletion
 * @param servingInventory deployment-signed inventory identity or explicit local mode
 */
public record TestSecretAuthorityTrustCohortPolicy(
        String scopeId,
        String cohortId,
        String instanceId,
        String startupId,
        String artifactFingerprint,
        Set<String> expectedInstanceIds,
        String authorityId,
        String protocolVersion,
        Duration heartbeatInterval,
        Duration leaseDuration,
        Duration recordRetention,
        ServingInventoryAttestation servingInventory) {

    private static final String DATABASE_SCOPE_PREFIX = "test-secret/";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Normalizes and validates the complete fail-closed cohort contract. */
    public TestSecretAuthorityTrustCohortPolicy {
        scopeId = normalized(scopeId);
        cohortId = normalized(cohortId);
        instanceId = normalized(instanceId);
        startupId = normalized(startupId);
        artifactFingerprint = normalized(artifactFingerprint);
        authorityId = normalized(authorityId);
        protocolVersion = normalized(protocolVersion);
        servingInventory = servingInventory == null
                ? ServingInventoryAttestation.localConfigured() : servingInventory;
        TreeSet<String> expected = new TreeSet<>();
        if (expectedInstanceIds != null) {
            expectedInstanceIds.stream()
                    .map(TestSecretAuthorityTrustCohortPolicy::normalized)
                    .forEach(expected::add);
        }
        expectedInstanceIds = Set.copyOf(expected);
        if (!IDENTIFIER.matcher(scopeId).matches()
                || DATABASE_SCOPE_PREFIX.length() + scopeId.length() > 255
                || !IDENTIFIER.matcher(cohortId).matches()
                || !IDENTIFIER.matcher(instanceId).matches()
                || !validUuid(startupId)
                || !FINGERPRINT.matcher(artifactFingerprint).matches()
                || !IDENTIFIER.matcher(authorityId).matches()
                || !IDENTIFIER.matcher(protocolVersion).matches()
                || expectedInstanceIds.isEmpty()
                || expectedInstanceIds.size()
                > TestSuiteStabilityAuthorityCohortPolicy.maximumReplicas()
                || !expectedInstanceIds.contains(instanceId)
                || expectedInstanceIds.stream().anyMatch(
                value -> !IDENTIFIER.matcher(value).matches())) {
            throw new IllegalArgumentException("Invalid test-secret authority trust cohort identity");
        }
        heartbeatInterval = bounded(heartbeatInterval, Duration.ofSeconds(1),
                Duration.ofMinutes(5), "cohort heartbeat interval");
        leaseDuration = bounded(leaseDuration, Duration.ofSeconds(3),
                Duration.ofMinutes(15), "cohort lease duration");
        recordRetention = bounded(recordRetention, Duration.ofHours(1),
                Duration.ofDays(30), "cohort record retention");
        if (leaseDuration.compareTo(heartbeatInterval.multipliedBy(3)) < 0
                || recordRetention.compareTo(leaseDuration) < 0) {
            throw new IllegalArgumentException(
                    "Test-secret authority trust cohort lease must cover three heartbeats");
        }
    }

    /**
     * Creates a local-configured policy for backwards-compatible single-authority deployments.
     *
     * @param scopeId stable serving-fleet scope
     * @param cohortId immutable deployment generation
     * @param instanceId local serving slot
     * @param startupId unique process-start UUID
     * @param artifactFingerprint immutable artifact identity
     * @param expectedInstanceIds complete locally configured inventory
     * @param authorityId exact test-secret authority identity
     * @param protocolVersion signed-response protocol generation
     * @param heartbeatInterval local heartbeat interval
     * @param leaseDuration database-clock membership lease
     * @param recordRetention expired-row retention
     */
    public TestSecretAuthorityTrustCohortPolicy(
            String scopeId,
            String cohortId,
            String instanceId,
            String startupId,
            String artifactFingerprint,
            Set<String> expectedInstanceIds,
            String authorityId,
            String protocolVersion,
            Duration heartbeatInterval,
            Duration leaseDuration,
            Duration recordRetention) {
        this(scopeId, cohortId, instanceId, startupId, artifactFingerprint,
                expectedInstanceIds, authorityId, protocolVersion, heartbeatInterval,
                leaseDuration, recordRetention,
                ServingInventoryAttestation.localConfigured());
    }

    /**
     * Projects this domain policy into the mature database cohort authority.
     *
     * <p>The namespaced scope prevents collisions with suite-stability authority cohorts. This is
     * an internal persistence compatibility projection, not a public wire representation.</p>
     *
     * @return equivalent database-authority policy with an isolated scope namespace
     */
    public TestSuiteStabilityAuthorityCohortPolicy asDatabasePolicy() {
        TestSuiteStabilityAuthorityCohortPolicy.ServingInventoryAttestation databaseInventory =
                servingInventory.externallyAttested()
                        ? new TestSuiteStabilityAuthorityCohortPolicy.ServingInventoryAttestation(
                        TestSuiteStabilityAuthorityCohortPolicy.ServingInventoryAttestation
                                .SCHEMA_VERSION,
                        true, databaseInventorySourceType(servingInventory.sourceType()),
                        servingInventory.revision(),
                        servingInventory.materialFingerprint(),
                        servingInventory.policyFingerprint(), servingInventory.expiresAt())
                        : TestSuiteStabilityAuthorityCohortPolicy.ServingInventoryAttestation
                        .localConfigured();
        return new TestSuiteStabilityAuthorityCohortPolicy(
                DATABASE_SCOPE_PREFIX + scopeId, cohortId, instanceId, startupId,
                artifactFingerprint, expectedInstanceIds, authorityId, protocolVersion,
                heartbeatInterval, leaseDuration, recordRetention,
                databaseInventory);
    }

    /**
     * Immutable signed-inventory identity bound into the cohort policy fingerprint.
     *
     * @param schemaVersion attestation binding generation
     * @param externallyAttested whether independent signatures establish the member set
     * @param sourceType inventory authority implementation type
     * @param revision monotonic external inventory revision, or zero in local mode
     * @param materialFingerprint signed inventory material identity, blank in local mode
     * @param policyFingerprint external inventory-policy identity, blank in local mode
     * @param expiresAt hard signed-inventory deadline, null in local mode
     */
    public record ServingInventoryAttestation(
            String schemaVersion,
            boolean externallyAttested,
            String sourceType,
            long revision,
            String materialFingerprint,
            String policyFingerprint,
            Instant expiresAt) {

        /** Current test-secret inventory attestation binding generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSecretAuthorityServingInventoryAttestation.v1";

        /** Rejects ambiguous partial external-attestation identity. */
        public ServingInventoryAttestation {
            schemaVersion = normalized(schemaVersion);
            sourceType = normalized(sourceType);
            materialFingerprint = normalized(materialFingerprint);
            policyFingerprint = normalized(policyFingerprint);
            boolean external = externallyAttested
                    && ("STATIC_SIGNED_ED25519_M_OF_N".equals(sourceType)
                    || DynamicTestSecretAuthorityServingInventoryAuthority.SOURCE_TYPE.equals(
                    sourceType))
                    && revision > 0
                    && FINGERPRINT.matcher(materialFingerprint).matches()
                    && FINGERPRINT.matcher(policyFingerprint).matches()
                    && expiresAt != null;
            boolean local = !externallyAttested
                    && "LOCAL_CONFIGURED".equals(sourceType)
                    && revision == 0 && materialFingerprint.isEmpty()
                    && policyFingerprint.isEmpty() && expiresAt == null;
            if (!SCHEMA_VERSION.equals(schemaVersion) || !(external || local)) {
                throw new IllegalArgumentException(
                        "Invalid test-secret serving inventory attestation");
            }
        }

        /** @return explicit local configuration identity */
        public static ServingInventoryAttestation localConfigured() {
            return new ServingInventoryAttestation(SCHEMA_VERSION, false,
                    "LOCAL_CONFIGURED", 0, "", "", null);
        }

        /**
         * Freezes one current verified inventory observation into a cohort policy.
         *
         * @param observation current independently verified inventory
         * @return immutable external attestation binding
         */
        public static ServingInventoryAttestation external(
                TestSecretAuthorityServingInventoryAuthority.Observation observation) {
            if (observation == null || !observation.available()
                    || !observation.externallyAttested()) {
                throw new IllegalArgumentException(
                        "A current signed test-secret serving inventory is required");
            }
            return new ServingInventoryAttestation(SCHEMA_VERSION, true,
                    observation.sourceType(), observation.revision(),
                    observation.materialFingerprint(), observation.policyFingerprint(),
                    observation.expiresAt());
        }
    }

    private static String databaseInventorySourceType(String sourceType) {
        if (DynamicTestSecretAuthorityServingInventoryAuthority.SOURCE_TYPE.equals(sourceType)) {
            return DynamicTestSuiteStabilityServingInventoryAuthority.SOURCE_TYPE;
        }
        return sourceType;
    }

    private static Duration bounded(
            Duration value, Duration minimum, Duration maximum, String label) {
        if (value == null || value.getNano() != 0
                || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(label + " is outside its whole-second bound");
        }
        return value;
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
