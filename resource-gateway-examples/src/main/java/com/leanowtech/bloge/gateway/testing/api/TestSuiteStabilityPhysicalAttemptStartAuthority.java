package com.leanowtech.bloge.gateway.testing.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Opaque provider boundary for starting physically isolated suite-stability attempts.
 *
 * <p>Implementations must resolve the command's opaque execution envelope inside an isolated
 * test/staging runtime, make start idempotent by command id, and return a detached attestation.
 * Java object provenance is not a trust boundary; callers must independently verify the result.</p>
 */
public interface TestSuiteStabilityPhysicalAttemptStartAuthority {

    /**
     * Describes the exact provider deployment selected for a subsequent start call.
     *
     * @return current payload-free provider identity and physical isolation capabilities
     */
    Descriptor descriptor();

    /**
     * Requests an idempotent start of one exact reserved attempt.
     *
     * @param command challenge-bound content-addressed start command
     * @return detached provider attestation; never {@code null}
     */
    TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation start(
            TestSuiteStabilityPhysicalAttemptStartCommand command);

    /**
     * Provider identity frozen before dispatch and rebound during receipt verification.
     *
     * @param schemaVersion exact descriptor generation
     * @param providerId stable isolated-runtime provider
     * @param deploymentId exact provider workload generation
     * @param keyId active detached-signature key
     * @param available whether new starts may be served
     * @param isolationModes physical boundaries this deployment can prove
     * @param maximumStartLatency provider-declared confirmation bound from 100 ms through 5 min
     */
    record Descriptor(
            String schemaVersion,
            String providerId,
            String deploymentId,
            String keyId,
            boolean available,
            Set<TestSuiteStabilityAttemptCancellationReceipt.IsolationMode> isolationModes,
            Duration maximumStartLatency) {

        /** Exact physical-attempt start-authority descriptor generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptStartAuthorityDescriptor.v1";
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,210}");

        /** Requires exact identity, non-empty mode set, and bounded millisecond latency. */
        public Descriptor {
            schemaVersion = required(schemaVersion, "schemaVersion");
            providerId = requiredIdentifier(providerId, "providerId");
            deploymentId = requiredIdentifier(deploymentId, "deploymentId");
            keyId = requiredIdentifier(keyId, "keyId");
            isolationModes = Set.copyOf(Objects.requireNonNull(
                    isolationModes, "isolationModes"));
            maximumStartLatency = Objects.requireNonNull(
                    maximumStartLatency, "maximumStartLatency");
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || isolationModes.isEmpty()
                    || maximumStartLatency.compareTo(Duration.ofMillis(100)) < 0
                    || maximumStartLatency.compareTo(Duration.ofMinutes(5)) > 0
                    || !maximumStartLatency.equals(
                    Duration.ofMillis(maximumStartLatency.toMillis()))) {
                throw new IllegalArgumentException(
                        "Invalid suite-stability physical-attempt start descriptor");
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
