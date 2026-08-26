package com.leanowtech.bloge.gateway.testing.world.draft;

/** Adapter for the completed and verified run-evidence payload companion repository. */
public final class RunEvidenceWorldDraftSourceAdapter implements WorldDraftSourceAdapter {
    private final WorldDraftSourceRepository repository;
    public RunEvidenceWorldDraftSourceAdapter(WorldDraftSourceRepository repository) {
        this.repository = WorldDraftSourceAdapterSupport.require(repository,
                WorldDraftSourceRef.Kind.RUN_EVIDENCE_PAYLOAD);
    }
    @Override public WorldDraftSourceRef.Kind kind() { return WorldDraftSourceRef.Kind.RUN_EVIDENCE_PAYLOAD; }
    @Override public WorldDraftSourceAuthority.SourceMetadata inspect(WorldDraftSourceRef source,
                                                                        WorldDraftCandidateService.Access access) {
        return WorldDraftSourceAdapterSupport.inspect(repository, source, access, kind());
    }
    @Override public WorldDraftSourceAuthority.SourcePayload read(WorldDraftSourceAuthority.SourceMetadata metadata,
                                                                    WorldDraftCandidateService.Access access) {
        return WorldDraftSourceAdapterSupport.read(repository, metadata, access, kind());
    }
}
