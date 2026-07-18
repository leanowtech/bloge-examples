package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Exact deployment-owned membership and lease policy for authority-trust convergence.
 *
 * <p>The expected instance set is configuration authority, not runtime discovery. Every process in
 * one deployment cohort must receive the same cohort id, artifact fingerprint, authority id,
 * protocol version and exact serving-instance set. A live process whose identity is absent from
 * that set is a blocker rather than an automatically trusted member.</p>
 *
 * @param scopeId stable serving-fleet scope across deployment generations
 * @param cohortId immutable deployment generation identity
 * @param instanceId stable serving slot represented by this process
 * @param startupId unique UUID for this process start
 * @param artifactFingerprint exact immutable image or application SHA-256
 * @param expectedInstanceIds complete configured serving-slot inventory
 * @param authorityId exact signed-decision authority identity
 * @param protocolVersion exact Resource Gateway integration protocol generation
 * @param heartbeatInterval local publication interval
 * @param leaseDuration database-clock liveness window, at least three heartbeat intervals
 * @param recordRetention expired membership retention before bounded deletion
 * @param servingInventory externally attested inventory identity or explicit local mode
 */
public record TestSuiteStabilityAuthorityCohortPolicy(
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

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final int MAXIMUM_REPLICAS = 256;

    /** Normalizes and validates the complete fail-closed cohort contract. */
    public TestSuiteStabilityAuthorityCohortPolicy {
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
            expectedInstanceIds.stream().map(
                    TestSuiteStabilityAuthorityCohortPolicy::normalized).forEach(expected::add);
        }
        expectedInstanceIds = Set.copyOf(expected);
        if (!IDENTIFIER.matcher(scopeId).matches()
                || !IDENTIFIER.matcher(cohortId).matches()
                || !IDENTIFIER.matcher(instanceId).matches()
                || !validUuid(startupId)
                || !FINGERPRINT.matcher(artifactFingerprint).matches()
                || !IDENTIFIER.matcher(authorityId).matches()
                || !IDENTIFIER.matcher(protocolVersion).matches()
                || expectedInstanceIds.isEmpty()
                || expectedInstanceIds.size() > MAXIMUM_REPLICAS
                || !expectedInstanceIds.contains(instanceId)
                || expectedInstanceIds.stream().anyMatch(
                value -> !IDENTIFIER.matcher(value).matches())) {
            throw new IllegalArgumentException("Invalid stability authority cohort identity");
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
                    "Stability authority cohort lease must cover three heartbeats");
        }
    }

    /** @return the maximum bounded cohort cardinality accepted by the protocol */
    public static int maximumReplicas() {
        return MAXIMUM_REPLICAS;
    }

    /**
     * Computes the exact shared policy identity, excluding only process-local instance/startup.
     *
     * @param objectMapper canonical protocol mapper
     * @return SHA-256 cohort policy identity
     */
    public String cohortFingerprint(ObjectMapper objectMapper) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.testSuiteStabilityAuthorityCohortPolicy.v1"),
                Map.entry("scopeId", scopeId),
                Map.entry("cohortId", cohortId),
                Map.entry("artifactFingerprint", artifactFingerprint),
                Map.entry("expectedInstanceIds", expectedInstanceIds.stream().sorted().toList()),
                Map.entry("authorityId", authorityId),
                Map.entry("protocolVersion", protocolVersion),
                Map.entry("heartbeatIntervalSeconds", heartbeatInterval.toSeconds()),
                Map.entry("leaseDurationSeconds", leaseDuration.toSeconds()),
                Map.entry("recordRetentionSeconds", recordRetention.toSeconds()),
                Map.entry("servingInventory", servingInventory)));
    }

    /**
     * Immutable external-inventory identity bound into every cohort member policy fingerprint.
     *
     * @param schemaVersion binding protocol generation
     * @param externallyAttested whether deployment authorities signed the exact set
     * @param sourceType inventory authority type
     * @param revision monotonic external revision, or zero in local configured mode
     * @param materialFingerprint signed material identity, blank in local mode
     * @param policyFingerprint external inventory policy identity, blank in local mode
     * @param expiresAt hard external validity deadline, null in local mode
     */
    public record ServingInventoryAttestation(
            String schemaVersion,
            boolean externallyAttested,
            String sourceType,
            long revision,
            String materialFingerprint,
            String policyFingerprint,
            Instant expiresAt) {

        /** Current immutable cohort inventory-binding generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityServingInventoryAttestation.v1";

        /** Rejects ambiguous partial external-attestation identity. */
        public ServingInventoryAttestation {
            schemaVersion = normalized(schemaVersion);
            sourceType = normalized(sourceType);
            materialFingerprint = normalized(materialFingerprint);
            policyFingerprint = normalized(policyFingerprint);
            boolean external = externallyAttested
                    && ("STATIC_SIGNED_ED25519_M_OF_N".equals(sourceType)
                    || DynamicTestSuiteStabilityServingInventoryAuthority.SOURCE_TYPE.equals(
                    sourceType))
                    && revision > 0
                    && FINGERPRINT.matcher(materialFingerprint).matches()
                    && FINGERPRINT.matcher(policyFingerprint).matches()
                    && expiresAt != null;
            boolean local = !externallyAttested && "LOCAL_CONFIGURED".equals(sourceType)
                    && revision == 0 && materialFingerprint.isEmpty()
                    && policyFingerprint.isEmpty() && expiresAt == null;
            if (!SCHEMA_VERSION.equals(schemaVersion) || !(external || local)) {
                throw new IllegalArgumentException(
                        "Invalid stability authority serving-inventory attestation");
            }
        }

        /** @return explicit backward-compatible local configuration identity */
        public static ServingInventoryAttestation localConfigured() {
            return new ServingInventoryAttestation(SCHEMA_VERSION, false,
                    "LOCAL_CONFIGURED", 0, "", "", null);
        }

        /** @return immutable binding derived from one currently verified external observation */
        public static ServingInventoryAttestation external(
                TestSuiteStabilityServingInventoryAuthority.Observation observation) {
            if (observation == null || !observation.available()
                    || !observation.externallyAttested()) {
                throw new IllegalArgumentException(
                        "A current external serving inventory is required");
            }
            return new ServingInventoryAttestation(SCHEMA_VERSION, true,
                    observation.sourceType(), observation.revision(),
                    observation.materialFingerprint(), observation.policyFingerprint(),
                    observation.expiresAt());
        }
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
