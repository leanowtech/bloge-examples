package com.leanowtech.bloge.gateway.testing.world.draft;

import java.util.Optional;

/** CAS boundary for immutable candidate revisions. */
public interface WorldDraftCandidateRepository {
    WorldDraftCandidate create(WorldDraftCandidate candidate);

    Optional<WorldDraftCandidate> find(String tenantId, String candidateId);

    boolean compareAndSet(WorldDraftCandidate expected, WorldDraftCandidate replacement);
}
