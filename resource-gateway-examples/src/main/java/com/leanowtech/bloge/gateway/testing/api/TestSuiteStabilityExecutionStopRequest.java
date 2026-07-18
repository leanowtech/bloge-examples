package com.leanowtech.bloge.gateway.testing.api;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Exact terminal stop intent for a suite-stability parent execution.
 *
 * @param stabilityRunId deterministic parent identity
 * @param tenantId verified tenant scope
 * @param environmentId verified non-production environment
 * @param clientRequestId parent idempotency identity
 * @param requestFingerprint immutable execution request fingerprint
 * @param classification frozen suite classification
 * @param reason terminal stop category
 * @param failureCode bounded machine-stable diagnostic
 * @param actorId authenticated cancellation actor or server worker
 * @param retention stop tombstone retention
 */
public record TestSuiteStabilityExecutionStopRequest(
        String stabilityRunId,
        String tenantId,
        String environmentId,
        String clientRequestId,
        String requestFingerprint,
        String classification,
        TestSuiteStabilityExecutionStop.Reason reason,
        String failureCode,
        String actorId,
        Duration retention) {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Validates the persistence-safe stop authority. */
    public TestSuiteStabilityExecutionStopRequest {
        stabilityRunId = normalized(stabilityRunId);
        tenantId = normalized(tenantId);
        environmentId = normalized(environmentId).toLowerCase(Locale.ROOT);
        clientRequestId = normalized(clientRequestId);
        requestFingerprint = normalized(requestFingerprint);
        classification = normalized(classification).toUpperCase(Locale.ROOT);
        reason = java.util.Objects.requireNonNull(reason, "reason");
        failureCode = normalized(failureCode).toUpperCase(Locale.ROOT);
        actorId = normalized(actorId);
        retention = java.util.Objects.requireNonNull(retention, "retention");
        if (!valid(stabilityRunId) || !valid(tenantId)
                || !Set.of("test", "staging").contains(environmentId)
                || !valid(clientRequestId)
                || !FINGERPRINT.matcher(requestFingerprint).matches()
                || !Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED")
                .contains(classification)
                || !CODE.matcher(failureCode).matches() || !valid(actorId)
                || retention.toMillis() % 1_000 != 0
                || retention.compareTo(Duration.ofHours(1)) < 0
                || retention.compareTo(Duration.ofDays(3650)) > 0) {
            throw new IllegalArgumentException("Invalid suite-stability execution stop request");
        }
    }

    private static boolean valid(String value) {
        return IDENTIFIER.matcher(value).matches();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
