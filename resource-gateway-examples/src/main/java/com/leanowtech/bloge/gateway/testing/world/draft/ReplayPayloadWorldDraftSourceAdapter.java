package com.leanowtech.bloge.gateway.testing.world.draft;

/** Adapter for the authoritative replay-payload repository boundary. */
public final class ReplayPayloadWorldDraftSourceAdapter implements WorldDraftSourceAdapter {
    private final WorldDraftSourceRepository repository;
    public ReplayPayloadWorldDraftSourceAdapter(WorldDraftSourceRepository repository) {
        this.repository = WorldDraftSourceAdapterSupport.require(repository, WorldDraftSourceRef.Kind.REPLAY_PAYLOAD);
    }
    @Override public WorldDraftSourceRef.Kind kind() { return WorldDraftSourceRef.Kind.REPLAY_PAYLOAD; }
    @Override public WorldDraftSourceAuthority.SourceMetadata inspect(WorldDraftSourceRef source,
                                                                        WorldDraftCandidateService.Access access) {
        return WorldDraftSourceAdapterSupport.inspect(repository, source, access, kind());
    }
    @Override public WorldDraftSourceAuthority.SourcePayload read(WorldDraftSourceAuthority.SourceMetadata metadata,
                                                                    WorldDraftCandidateService.Access access) {
        return WorldDraftSourceAdapterSupport.read(repository, metadata, access, kind());
    }
}
