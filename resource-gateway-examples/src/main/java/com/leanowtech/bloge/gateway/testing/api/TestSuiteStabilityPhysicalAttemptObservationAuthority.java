package com.leanowtech.bloge.gateway.testing.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Opaque provider boundary for observing physical-attempt lifecycle state.
 *
 * <p>Implementations must resolve the exact original start command, retain signed lifecycle facts
 * for at least the declared window, and make observations idempotent by command id. A missing fact
 * must be returned as non-confirming rather than translated into successful non-start or
 * termination. Callers must independently verify every detached attestation.</p>
 */
public interface TestSuiteStabilityPhysicalAttemptObservationAuthority {

    /**
     * Describes the exact provider deployment selected for a subsequent observation.
     *
     * @return current payload-free provider identity, latency, and retention guarantees
     */
    Descriptor descriptor();

    /**
     * Requests an idempotent observation of one exact physical attempt.
     *
     * @param command challenge-bound content-addressed observation command
     * @return detached provider attestation; never {@code null}
     */
    TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation observe(
            TestSuiteStabilityPhysicalAttemptObservationCommand command);

    /**
     * Provider identity and bounded lifecycle-fact service guarantees.
     *
     * @param schemaVersion exact descriptor generation
     * @param providerId stable isolated-runtime provider
     * @param deploymentId exact provider workload generation
     * @param keyId active detached-signature key
     * @param available whether observations may currently be served
     * @param isolationModes physical boundaries this deployment can prove
     * @param maximumObservationLatency provider confirmation bound from 100 ms through 5 min
     * @param minimumStateRetention minimum signed-fact retention from 1 min through 30 days
     */
    record Descriptor(
            String schemaVersion,
            String providerId,
            String deploymentId,
            String keyId,
            boolean available,
            Set<TestSuiteStabilityAttemptCancellationReceipt.IsolationMode> isolationModes,
            Duration maximumObservationLatency,
            Duration minimumStateRetention) {

        /** Exact physical-attempt observation-authority descriptor generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptObservationAuthorityDescriptor.v1";
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,210}");

        /** Requires exact identity, non-empty modes, and bounded millisecond guarantees. */
        public Descriptor {
            schemaVersion = required(schemaVersion, "schemaVersion");
            providerId = requiredIdentifier(providerId, "providerId");
            deploymentId = requiredIdentifier(deploymentId, "deploymentId");
            keyId = requiredIdentifier(keyId, "keyId");
            isolationModes = Set.copyOf(Objects.requireNonNull(
                    isolationModes, "isolationModes"));
            maximumObservationLatency = Objects.requireNonNull(
                    maximumObservationLatency, "maximumObservationLatency");
            minimumStateRetention = Objects.requireNonNull(
                    minimumStateRetention, "minimumStateRetention");
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || isolationModes.isEmpty()
                    || maximumObservationLatency.compareTo(Duration.ofMillis(100)) < 0
                    || maximumObservationLatency.compareTo(Duration.ofMinutes(5)) > 0
                    || minimumStateRetention.compareTo(Duration.ofMinutes(1)) < 0
                    || minimumStateRetention.compareTo(Duration.ofDays(30)) > 0
                    || !millisecondExact(maximumObservationLatency)
                    || !millisecondExact(minimumStateRetention)) {
                throw new IllegalArgumentException(
                        "Invalid suite-stability physical-attempt observation descriptor");
            }
        }

        private static boolean millisecondExact(Duration value) {
            return value.equals(Duration.ofMillis(value.toMillis()));
        }

        private static String required(String value, String field) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return normalized;
        }

        private static String requiredIdentifier(String value, String field) {
            String normalized = required(value, field);
            if (!IDENTIFIER.matcher(normalized).matches()) {
                throw new IllegalArgumentException(field + " is invalid");
            }
            return normalized;
        }
    }
}
