package com.leanowtech.bloge.gateway.testing.api;

import java.time.Duration;
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
        Duration recordRetention) {

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
     * Projects this domain policy into the mature database cohort authority.
     *
     * <p>The namespaced scope prevents collisions with suite-stability authority cohorts. This is
     * an internal persistence compatibility projection, not a public wire representation.</p>
     *
     * @return equivalent database-authority policy with an isolated scope namespace
     */
    public TestSuiteStabilityAuthorityCohortPolicy asDatabasePolicy() {
        return new TestSuiteStabilityAuthorityCohortPolicy(
                DATABASE_SCOPE_PREFIX + scopeId, cohortId, instanceId, startupId,
                artifactFingerprint, expectedInstanceIds, authorityId, protocolVersion,
                heartbeatInterval, leaseDuration, recordRetention,
                TestSuiteStabilityAuthorityCohortPolicy.ServingInventoryAttestation
                        .localConfigured());
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
