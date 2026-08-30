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
    /** Claims an idempotency coordinate; returns Acquired, Replay, Busy or Conflict. */
    ClaimResult claim(CommandKey key, String requestFingerprint, com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision expectedRevision);
    /** Stages an invisible revision; may report validation or typed CAS/projection failures. */
    StagedApiResource stage(CommandLease lease, String connectionId, ApiResourceCommand command);
    /** Atomically promotes a stage; may report STAGE_MISSING, CAS_MISMATCH, projection or receipt failures. */
    CommandReceipt commit(CommandLease lease, CommandReceipt finalReceipt);
    /** Fenced failure removes only the matching stage and journals its typed code. */
    void fail(CommandLease lease, CommandFailureCode failureCode);
    /** Reads only the committed head in a scope; staged rows are never returned. */
    Optional<StoredApiResource> findHead(AuthoringScope scope, String resourceId);
    /** Reads only a committed exact revision in a scope; staged rows are never returned. */
    Optional<StoredApiResource> findRevision(AuthoringScope scope, String resourceId, long revision);
}
