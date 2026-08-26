package com.leanowtech.bloge.gateway.testing.world.draft;

/** Server-owned authority that issues an approval receipt from the authenticated actor. */
@FunctionalInterface
public interface WorldDraftApprovalAuthority {
    WorldDraftApproval issue(WorldDraftCandidate candidate, WorldDraftCandidateService.Access access);
}
