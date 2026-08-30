package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;

/**
 * Persistence boundary for claiming one scoped authoring command.
 *
 * <p>The claim coordinate is deliberately independent from a resource or
 * connection projection.  A caller can therefore use this seam to reserve an
 * idempotency attempt before selecting the concrete metadata store.  Claim
 * implementations must compare the complete request fingerprint and
 * optimistic-concurrency expectation when replaying an existing coordinate;
 * they must never silently reinterpret a changed expectation.</p>
 */
public interface AuthoringCommandClaimStore {
    /**
     * Claims or re-enters an idempotent command coordinate.
     *
     * @param key complete scope, actor, endpoint, target, and idempotency key
     * @param requestFingerprint deterministic payload-free request fingerprint
     * @param expectedRevision outer command optimistic-concurrency expectation
     * @return acquired, replay, busy, or conflict result
     */
    ClaimResult claim(CommandKey key, String requestFingerprint, ExpectedRevision expectedRevision);
}
