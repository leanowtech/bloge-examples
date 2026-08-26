package com.leanowtech.bloge.gateway.testing.world.draft;

/** One server-owned source-kind adapter; payload access remains a separate operation. */
public interface WorldDraftSourceAdapter {
    WorldDraftSourceRef.Kind kind();

    WorldDraftSourceAuthority.SourceMetadata inspect(WorldDraftSourceRef source,
                                                       WorldDraftCandidateService.Access access);

    WorldDraftSourceAuthority.SourcePayload read(WorldDraftSourceAuthority.SourceMetadata metadata,
                                                  WorldDraftCandidateService.Access access);
}
