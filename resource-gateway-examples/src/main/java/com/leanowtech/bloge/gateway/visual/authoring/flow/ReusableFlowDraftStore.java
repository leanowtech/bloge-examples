package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.Optional;

/** Atomic revision/head/CAS/idempotency authority for reusable Flow drafts. */
public interface ReusableFlowDraftStore {
    /** Saves one already-compiled intent or returns its exact committed replay. */
    ReusableFlowSaveResult save(ReusableFlowSaveIntent intent);
    /** Reads the current committed draft in one exact scope. */
    Optional<ReusableFlowDraft> findHead(AuthoringScope scope, String flowId);
    /** Reads one exact committed historical revision in one exact scope. */
    Optional<ReusableFlowDraft> findRevision(AuthoringScope scope, String flowId, int revision);
}
