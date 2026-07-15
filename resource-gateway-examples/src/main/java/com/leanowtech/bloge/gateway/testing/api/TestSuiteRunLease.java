package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;

/**
 * Renewable ownership claim for one actively executing suite run.
 *
 * <p>The owner id identifies a runtime instance, not a user. A lease that has reached
 * {@link #expiresAt()} cannot be revived; the abandoned-run reconciler may then claim the row.</p>
 *
 * @param ownerId stable process-instance identifier
 * @param expiresAt exclusive lease deadline
 */
public record TestSuiteRunLease(String ownerId, Instant expiresAt) {
    /** Rejects ownerless and timeless claims before they reach persistence. */
    public TestSuiteRunLease {
        ownerId = ownerId == null ? "" : ownerId.trim();
        if (ownerId.isBlank() || expiresAt == null) {
            throw new IllegalArgumentException("Suite-run lease owner and expiry are required");
        }
    }
}
