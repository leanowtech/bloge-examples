package com.leanowtech.bloge.gateway.testing.api;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Exact internal intent for acquiring one cross-replica suite-stability execution lease.
 *
 * <p>The value contains no fixture, context, child evidence, or credential. Scope and immutable
 * request identity are repeated deliberately so the persistence adapter can reject cross-scope or
 * changed-intent takeover before executing another suite attempt.</p>
 *
 * @param stabilityRunId deterministic scope-and-request execution identity
 * @param tenantId verified tenant scope
 * @param environmentId verified non-production environment
 * @param clientRequestId caller-stable idempotency identity
 * @param requestFingerprint canonical immutable request fingerprint
 * @param ownerId opaque execution-invocation owner identity
 * @param leaseDuration database-clock lease duration
 */
public record TestSuiteStabilityLeaseRequest(
        String stabilityRunId,
        String tenantId,
        String environmentId,
        String clientRequestId,
        String requestFingerprint,
        String ownerId,
        Duration leaseDuration
) {
    /** Smallest lease accepted by the cross-replica protocol. */
    public static final Duration MINIMUM_LEASE = Duration.ofSeconds(5);
    /** Largest lease accepted; heartbeats must maintain longer work. */
    public static final Duration MAXIMUM_LEASE = Duration.ofHours(1);
    private static final Pattern STABILITY_RUN_ID = Pattern.compile("stability-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    /** Normalizes and bounds every persistence coordinate before database access. */
    public TestSuiteStabilityLeaseRequest {
        stabilityRunId = normalized(stabilityRunId);
        tenantId = normalized(tenantId);
        environmentId = normalized(environmentId);
        clientRequestId = normalized(clientRequestId);
        requestFingerprint = normalized(requestFingerprint);
        ownerId = normalized(ownerId);
        if (!STABILITY_RUN_ID.matcher(stabilityRunId).matches()
                || tenantId.isBlank() || tenantId.length() > 255
                || environmentId.isBlank() || environmentId.length() > 255
                || clientRequestId.isBlank() || clientRequestId.length() > 255
                || !FINGERPRINT.matcher(requestFingerprint).matches()
                || !OWNER.matcher(ownerId).matches()
                || leaseDuration == null
                || leaseDuration.compareTo(MINIMUM_LEASE) < 0
                || leaseDuration.compareTo(MAXIMUM_LEASE) > 0
                || leaseDuration.toMillis() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    "A complete bounded suite-stability lease request is required");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
