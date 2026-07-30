package com.leanowtech.bloge.gateway.visual.authoring.testing;

import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.EvidenceRecord;

import java.util.List;
import java.util.Optional;

/**
 * Immutable enterprise-scoped authoring test evidence store.
 */
public interface AuthoringTestEvidenceRepository {

    EvidenceRecord create(EvidenceRecord evidence);

    Optional<EvidenceRecord> find(AuthoringTestScope scope, String runId);

    List<EvidenceRecord> findByDraft(AuthoringTestScope scope, String draftId);
}
