package com.leanowtech.bloge.gateway.testing.world.draft;

/** Narrow metadata-first adapter port for one governed source repository. */
public interface WorldDraftSourceRepository {
    WorldDraftSourceRef.Kind kind();

    WorldDraftSourceAuthority.SourceMetadata inspect(WorldDraftSourceRef source,
                                                       WorldDraftCandidateService.Access access);

    WorldDraftSourceAuthority.SourcePayload read(WorldDraftSourceAuthority.SourceMetadata metadata,
                                                  WorldDraftCandidateService.Access access);
}
