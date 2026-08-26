package com.leanowtech.bloge.gateway.testing.world.draft;

/** Server-owned authority that issues a publication receipt after policy checks. */
@FunctionalInterface
public interface WorldDraftPublicationAuthority {
    WorldDraftPublicationReceipt issue(WorldDraftCandidate candidate, WorldDraftCandidateService.Access access);
}
