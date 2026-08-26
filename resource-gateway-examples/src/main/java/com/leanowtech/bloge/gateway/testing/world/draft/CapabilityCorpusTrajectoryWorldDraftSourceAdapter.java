package com.leanowtech.bloge.gateway.testing.world.draft;

/** Adapter for an owner-reviewed, published capability corpus trajectory repository. */
public final class CapabilityCorpusTrajectoryWorldDraftSourceAdapter implements WorldDraftSourceAdapter {
    private final WorldDraftSourceRepository repository;
    public CapabilityCorpusTrajectoryWorldDraftSourceAdapter(WorldDraftSourceRepository repository) {
        this.repository = WorldDraftSourceAdapterSupport.require(repository,
                WorldDraftSourceRef.Kind.CAPABILITY_CORPUS_TRAJECTORY);
    }
    @Override public WorldDraftSourceRef.Kind kind() { return WorldDraftSourceRef.Kind.CAPABILITY_CORPUS_TRAJECTORY; }
    @Override public WorldDraftSourceAuthority.SourceMetadata inspect(WorldDraftSourceRef source,
                                                                        WorldDraftCandidateService.Access access) {
        return WorldDraftSourceAdapterSupport.inspect(repository, source, access, kind());
    }
    @Override public WorldDraftSourceAuthority.SourcePayload read(WorldDraftSourceAuthority.SourceMetadata metadata,
                                                                    WorldDraftCandidateService.Access access) {
        return WorldDraftSourceAdapterSupport.read(repository, metadata, access, kind());
    }
}
