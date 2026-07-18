package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;
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
        Duration recordRetention) {

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
                Map.entry("recordRetentionSeconds", recordRetention.toSeconds())));
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
