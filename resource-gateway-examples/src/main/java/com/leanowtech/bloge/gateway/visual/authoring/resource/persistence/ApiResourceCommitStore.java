package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;

import java.util.Optional;

/** Deep persistence seam for staged, fenced and atomically committed resources. */
public interface ApiResourceCommitStore {
    /** Claims an idempotency coordinate. */
    ClaimResult claim(CommandKey key, String requestFingerprint, com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision expectedRevision);
    /** Stages an invisible revision and all required projections. */
    StagedApiResource stage(CommandLease lease, String connectionId, ApiResourceCommand command);
    /** Atomically promotes a stage and returns its durable receipt. */
    CommandReceipt commit(CommandLease lease);
    /** Fenced failure removes only the matching stage. */
    void fail(CommandLease lease);
    /** Reads only the committed head in a scope. */
    Optional<StoredApiResource> findHead(AuthoringScope scope, String resourceId);
    /** Reads only a committed exact revision in a scope. */
    Optional<StoredApiResource> findRevision(AuthoringScope scope, String resourceId, long revision);
}
