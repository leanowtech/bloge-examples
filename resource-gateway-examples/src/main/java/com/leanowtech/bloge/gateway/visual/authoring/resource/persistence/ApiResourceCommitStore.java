package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;

import java.util.Optional;

/**
 * Deep persistence seam for staged, fenced and atomically committed resources.
 * Implementations fail closed with {@link ApiResourceCommitStoreException.Code}
 * values including lease fencing/expiry, missing stage, CAS mismatch,
 * projection/receipt invalidity and integrity failure.
 */
public interface ApiResourceCommitStore {
    /** Claims an idempotency coordinate. */
    ClaimResult claim(CommandKey key, String requestFingerprint, com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision expectedRevision);
    /** Stages an invisible revision and all required projections. */
    StagedApiResource stage(CommandLease lease, String connectionId, ApiResourceCommand command);
    /** Atomically promotes a stage and returns the supplied durable receipt. */
    CommandReceipt commit(CommandLease lease, CommandReceipt finalReceipt);
    /** Fenced failure removes only the matching stage and journals its safe code. */
    void fail(CommandLease lease, String failureCode);
    /** Reads only the committed head in a scope. */
    Optional<StoredApiResource> findHead(AuthoringScope scope, String resourceId);
    /** Reads only a committed exact revision in a scope. */
    Optional<StoredApiResource> findRevision(AuthoringScope scope, String resourceId, long revision);
}
