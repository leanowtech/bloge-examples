package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

/** Result of claiming an idempotent command coordinate. */
public sealed interface ClaimResult permits ClaimResult.Acquired, ClaimResult.Replay, ClaimResult.Busy, ClaimResult.Conflict {
    /** Newly claimed or resumed attempt. */
    record Acquired(CommandLease lease, boolean resumed) implements ClaimResult { }
    /** Already committed receipt for the same request fingerprint. */
    record Replay(CommandReceipt receipt) implements ClaimResult { }
    /** A different live attempt owns the coordinate; retry after this instant. */
    record Busy(java.time.Instant retryAt) implements ClaimResult {
        public Busy {
            java.util.Objects.requireNonNull(retryAt, "retryAt");
        }
    }
    /** Same coordinate was used with a different request fingerprint. */
    record Conflict(String message) implements ClaimResult { }
}
