package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.Optional;

/** Atomic revision/head/CAS/idempotency authority for reusable Flow drafts. */
public interface ReusableFlowDraftStore {
    /** Saves one already-compiled intent or returns its exact committed replay. */
    ReusableFlowSaveResult save(ReusableFlowSaveIntent intent);
    /** Reads the current committed draft and its strong validator in one exact scope. */
    Optional<ReusableFlowStoredDraft> findHeadStored(AuthoringScope scope, String flowId);
    /** Reads one exact committed historical revision and its strong validator. */
    Optional<ReusableFlowStoredDraft> findRevisionStored(AuthoringScope scope, String flowId, int revision);
    /** Reads one exact committed draft subject without requiring callers to know its Flow identity. */
    Optional<ReusableFlowStoredDraft> findDraftRevisionStored(
            AuthoringScope scope, String draftId, int revision);
    /** Resolves one exact committed historical revision by opaque strong validator. */
    Optional<ReusableFlowStoredDraft> findRevisionByStrongEtag(
            AuthoringScope scope, String flowId, String strongEtag);

    /** Convenience read for callers that need only the current draft. */
    default Optional<ReusableFlowDraft> findHead(AuthoringScope scope, String flowId) {
        return findHeadStored(scope, flowId).map(ReusableFlowStoredDraft::draft);
    }

    /** Convenience read for callers that need only one historical draft. */
    default Optional<ReusableFlowDraft> findRevision(AuthoringScope scope, String flowId, int revision) {
        return findRevisionStored(scope, flowId, revision).map(ReusableFlowStoredDraft::draft);
    }
}
