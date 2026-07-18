package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Payload-free terminal tombstone that prevents a stopped parent from being resumed.
 *
 * @param stabilityRunId deterministic parent identity
 * @param tenantId verified tenant scope
 * @param environmentId verified environment
 * @param clientRequestId parent idempotency identity
 * @param requestFingerprint immutable execution request fingerprint
 * @param classification frozen suite classification
 * @param reason terminal stop category
 * @param failureCode bounded machine-stable diagnostic
 * @param actorId authenticated cancellation actor or server worker
 * @param createdAt database stop time
 * @param expiresAt tombstone retention expiry
 * @param recordFingerprint complete stop integrity fingerprint
 */
public record TestSuiteStabilityExecutionStop(
        String stabilityRunId,
        String tenantId,
        String environmentId,
        String clientRequestId,
        String requestFingerprint,
        String classification,
        Reason reason,
        String failureCode,
        String actorId,
        Instant createdAt,
        Instant expiresAt,
        String recordFingerprint) {

    /** Closed stop causes; none is interpreted as business stability evidence. */
    public enum Reason {
        CANCELLED,
        DEADLINE_EXCEEDED,
        WORKER_FAILED
    }

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Requires a complete retained stop tombstone. */
    public TestSuiteStabilityExecutionStop {
        stabilityRunId = normalized(stabilityRunId);
        tenantId = normalized(tenantId);
        environmentId = normalized(environmentId).toLowerCase(Locale.ROOT);
        clientRequestId = normalized(clientRequestId);
        requestFingerprint = normalized(requestFingerprint);
        classification = normalized(classification).toUpperCase(Locale.ROOT);
        reason = java.util.Objects.requireNonNull(reason, "reason");
        failureCode = normalized(failureCode).toUpperCase(Locale.ROOT);
        actorId = normalized(actorId);
        createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
        expiresAt = java.util.Objects.requireNonNull(expiresAt, "expiresAt");
        recordFingerprint = normalized(recordFingerprint);
        if (stabilityRunId.isBlank() || tenantId.isBlank()
                || !Set.of("test", "staging").contains(environmentId)
                || clientRequestId.isBlank()
                || !FINGERPRINT.matcher(requestFingerprint).matches()
                || !Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED")
                .contains(classification)
                || !CODE.matcher(failureCode).matches() || actorId.isBlank()
                || !expiresAt.isAfter(createdAt)
                || !FINGERPRINT.matcher(recordFingerprint).matches()) {
            throw new IllegalArgumentException("Invalid suite-stability execution stop");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
