package com.leanowtech.bloge.gateway.testing.world.draft;

/** Adapter for a server-issued golden-capture receipt repository. */
public final class GoldenCaptureWorldDraftSourceAdapter implements WorldDraftSourceAdapter {
    private final WorldDraftSourceRepository repository;
    public GoldenCaptureWorldDraftSourceAdapter(WorldDraftSourceRepository repository) {
        this.repository = WorldDraftSourceAdapterSupport.require(repository, WorldDraftSourceRef.Kind.GOLDEN_CAPTURE);
    }
    @Override public WorldDraftSourceRef.Kind kind() { return WorldDraftSourceRef.Kind.GOLDEN_CAPTURE; }
    @Override public WorldDraftSourceAuthority.SourceMetadata inspect(WorldDraftSourceRef source,
                                                                        WorldDraftCandidateService.Access access) {
        return WorldDraftSourceAdapterSupport.inspect(repository, source, access, kind());
    }
    @Override public WorldDraftSourceAuthority.SourcePayload read(WorldDraftSourceAuthority.SourceMetadata metadata,
                                                                    WorldDraftCandidateService.Access access) {
        return WorldDraftSourceAdapterSupport.read(repository, metadata, access, kind());
    }
}
