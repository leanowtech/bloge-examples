package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Cross-replica convergence proof for one physical-attempt provider-inventory generation.
 *
 * <p>The gate is a read-only local projection over a durable cohort authority. It must perform no
 * provider call and exposes no replica, provider, deployment, key, endpoint, or payload identity.
 * A capability projector compares its private generation fingerprint with the local inventory
 * observation before admitting readiness.</p>
 */
@FunctionalInterface
public interface TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate {

    /**
     * Returns current aggregate cohort convergence without provider I/O.
     *
     * @return current aggregate cohort observation
     */
    Observation observation();

    /**
     * Private cross-replica convergence observation.
     *
     * @param schemaVersion cohort observation generation
     * @param available whether the durable cohort state is currently trustworthy
     * @param status bounded convergence state
     * @param inventorySourceSequence exact inventory source generation
     * @param inventoryGenerationFingerprint exact private inventory generation identity
     * @param expectedReplicas complete attested cohort cardinality
     * @param readyReplicas replicas currently proving this exact generation
     * @param distinctInventoryGenerations distinct live generations across the cohort
     * @param observedAt durable-authority observation time
     */
    record Observation(
            String schemaVersion,
            boolean available,
            String status,
            long inventorySourceSequence,
            String inventoryGenerationFingerprint,
            int expectedReplicas,
            int readyReplicas,
            int distinctInventoryGenerations,
            Instant observedAt) {

        /** Current physical provider-inventory cohort observation generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptProviderInventoryCohortObservation.v1";
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
        private static final Pattern STATUS = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

        /** Enforces cardinality and exact-generation convergence relationships. */
        public Observation {
            schemaVersion = normalized(schemaVersion);
            status = normalized(status);
            inventoryGenerationFingerprint = normalized(inventoryGenerationFingerprint);
            if (!SCHEMA_VERSION.equals(schemaVersion) || !STATUS.matcher(status).matches()
                    || inventorySourceSequence < 1
                    || !FINGERPRINT.matcher(inventoryGenerationFingerprint).matches()
                    || expectedReplicas < 1 || expectedReplicas > 256
                    || readyReplicas < 0 || readyReplicas > expectedReplicas
                    || distinctInventoryGenerations < 0
                    || distinctInventoryGenerations > expectedReplicas
                    || observedAt == null
                    || "CONVERGED".equals(status)
                    != (readyReplicas == expectedReplicas
                    && distinctInventoryGenerations == 1)) {
                throw new IllegalArgumentException(
                        "Physical-attempt provider inventory cohort observation is invalid");
            }
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
