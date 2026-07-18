package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;

/**
 * Atomic result of appending one parent progress entry and renewing its exact execution fence.
 *
 * @param lease renewed owner/epoch fence
 * @param progress durable contiguous successor journal
 */
public record TestSuiteStabilityProgressCheckpoint(
        TestSuiteStabilityExecutionLease lease,
        TestSuiteStabilityExecutionProgress progress
) {
    /** Requires both projections to identify the same exact parent intent. */
    public TestSuiteStabilityProgressCheckpoint {
        lease = Objects.requireNonNull(lease, "lease");
        progress = Objects.requireNonNull(progress, "progress");
        if (!lease.stabilityRunId().equals(progress.stabilityRunId())
                || !lease.tenantId().equals(progress.tenantId())
                || !lease.environmentId().equals(progress.environmentId())
                || !lease.clientRequestId().equals(progress.clientRequestId())
                || !lease.requestFingerprint().equals(progress.requestFingerprint())) {
            throw new IllegalArgumentException(
                    "Progress checkpoint must match its exact suite-stability lease");
        }
    }
}
