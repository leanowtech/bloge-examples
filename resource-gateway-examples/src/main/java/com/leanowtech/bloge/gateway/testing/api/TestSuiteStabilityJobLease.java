package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Exact database-clock worker fence for one suite-stability queue job.
 *
 * @param jobId governed queue identity
 * @param tenantId tenant scope
 * @param environmentId non-production environment scope
 * @param requestFingerprint immutable execution intent
 * @param ownerId server-owned worker identity
 * @param epoch positive monotonically increasing ownership generation
 * @param expiresAt database-authority lease expiry
 */
public record TestSuiteStabilityJobLease(
        String jobId,
        String tenantId,
        String environmentId,
        String requestFingerprint,
        String ownerId,
        long epoch,
        Instant expiresAt) {

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Requires a complete immutable worker fence. */
    public TestSuiteStabilityJobLease {
        jobId = normalized(jobId);
        tenantId = normalized(tenantId);
        environmentId = normalized(environmentId);
        requestFingerprint = normalized(requestFingerprint);
        ownerId = normalized(ownerId);
        expiresAt = java.util.Objects.requireNonNull(expiresAt, "expiresAt");
        if (jobId.isBlank() || tenantId.isBlank()
                || !java.util.Set.of("test", "staging").contains(environmentId)
                || !FINGERPRINT.matcher(requestFingerprint).matches()
                || ownerId.isBlank() || epoch < 1) {
            throw new IllegalArgumentException("Invalid suite-stability job lease");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
