package com.leanowtech.bloge.gateway.testing.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Opaque provider boundary for terminating physically isolated stability attempts.
 *
 * <p>Implementations must make {@link #cancel} idempotent by command id and must never interpret a
 * missing attempt as successful termination. A caller still has to verify the returned detached
 * attestation; Java object provenance is not an authentication boundary.</p>
 */
public interface TestSuiteStabilityAttemptCancellationAuthority {

    /**
     * Describes the exact provider deployment used for a subsequent cancellation call.
     *
     * @return current payload-free provider identity and supported isolation boundaries
     */
    Descriptor descriptor();

    /**
     * Requests termination of one exact attempt.
     *
     * @param command challenge-bound, content-addressed cancellation command
     * @return detached provider attestation; never {@code null}
     */
    TestSuiteStabilityAttemptCancellationReceipt.Attestation cancel(
            TestSuiteStabilityAttemptCancellationCommand command);

    /**
     * Provider identity used before cancellation and rebound during receipt verification.
     *
     * @param schemaVersion exact descriptor generation
     * @param providerId stable provider identity
     * @param deploymentId exact provider workload generation
     * @param keyId active detached-signature key
     * @param available whether new cancellation commands may be served
     * @param isolationModes physical boundaries the provider can prove
     * @param maximumConfirmationLatency provider-declared upper bound from 100 ms through 5 min
     */
    record Descriptor(
            String schemaVersion,
            String providerId,
            String deploymentId,
            String keyId,
            boolean available,
            Set<TestSuiteStabilityAttemptCancellationReceipt.IsolationMode> isolationModes,
            Duration maximumConfirmationLatency) {

        /** Exact descriptor generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityAttemptCancellationAuthorityDescriptor.v1";
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,210}");

        /** Requires an exact identity, non-empty closed mode set, and bounded latency. */
        public Descriptor {
            schemaVersion = required(schemaVersion, "schemaVersion");
            providerId = requiredIdentifier(providerId, "providerId");
            deploymentId = requiredIdentifier(deploymentId, "deploymentId");
            keyId = requiredIdentifier(keyId, "keyId");
            isolationModes = Set.copyOf(Objects.requireNonNull(
                    isolationModes, "isolationModes"));
            maximumConfirmationLatency = Objects.requireNonNull(
                    maximumConfirmationLatency, "maximumConfirmationLatency");
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || isolationModes.isEmpty()
                    || maximumConfirmationLatency.compareTo(Duration.ofMillis(100)) < 0
                    || maximumConfirmationLatency.compareTo(Duration.ofMinutes(5)) > 0
                    || !maximumConfirmationLatency.equals(
                    Duration.ofMillis(maximumConfirmationLatency.toMillis()))) {
                throw new IllegalArgumentException(
                        "Invalid suite-stability attempt cancellation authority descriptor");
            }
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
