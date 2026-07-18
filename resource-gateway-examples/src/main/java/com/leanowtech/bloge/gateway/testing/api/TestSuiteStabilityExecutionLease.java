package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Database-authoritative fence for one live suite-stability parent execution.
 *
 * <p>Only the exact owner and epoch may renew, release, or publish terminal evidence. Expiry is an
 * observation, not authority supplied by a caller; repository adapters derive it from database
 * time and the bounded duration in {@link TestSuiteStabilityLeaseRequest}.</p>
 *
 * @param stabilityRunId deterministic stability execution identity
 * @param tenantId verified tenant scope
 * @param environmentId verified environment scope
 * @param clientRequestId caller-stable idempotency identity
 * @param requestFingerprint immutable execution intent
 * @param ownerId opaque invocation owner
 * @param epoch monotonically increasing takeover fence
 * @param expiresAt database-authoritative exclusive lease deadline
 */
public record TestSuiteStabilityExecutionLease(
        String stabilityRunId,
        String tenantId,
        String environmentId,
        String clientRequestId,
        String requestFingerprint,
        String ownerId,
        long epoch,
        Instant expiresAt
) {
    private static final Pattern STABILITY_RUN_ID = Pattern.compile("stability-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Rejects incomplete or impossible persisted lease projections. */
    public TestSuiteStabilityExecutionLease {
        stabilityRunId = normalized(stabilityRunId);
        tenantId = normalized(tenantId);
        environmentId = normalized(environmentId);
        clientRequestId = normalized(clientRequestId);
        requestFingerprint = normalized(requestFingerprint);
        ownerId = normalized(ownerId);
        if (!STABILITY_RUN_ID.matcher(stabilityRunId).matches()
                || tenantId.isBlank() || environmentId.isBlank() || clientRequestId.isBlank()
                || !FINGERPRINT.matcher(requestFingerprint).matches() || ownerId.isBlank()
                || epoch < 0 || expiresAt == null) {
            throw new IllegalArgumentException(
                    "A complete suite-stability execution lease is required");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
