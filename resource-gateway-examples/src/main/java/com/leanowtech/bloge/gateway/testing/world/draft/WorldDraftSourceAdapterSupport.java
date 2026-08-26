package com.leanowtech.bloge.gateway.testing.world.draft;

/** Shared fail-closed forwarding logic for source-kind-specific adapters. */
final class WorldDraftSourceAdapterSupport {
    private WorldDraftSourceAdapterSupport() { }

    static WorldDraftSourceRepository require(WorldDraftSourceRepository repository,
                                              WorldDraftSourceRef.Kind expected) {
        if (repository == null || repository.kind() != expected) throw invalid();
        return repository;
    }

    static WorldDraftSourceAuthority.SourceMetadata inspect(WorldDraftSourceRepository repository,
                                                            WorldDraftSourceRef source,
                                                            WorldDraftCandidateService.Access access,
                                                            WorldDraftSourceRef.Kind expected) {
        if (source == null || source.kind() != expected || access == null
                || !access.tenantId().equals(source.tenantId())) throw unauthorized();
        try {
            WorldDraftSourceAuthority.SourceMetadata metadata = repository.inspect(source, access);
            if (metadata == null || metadata.source() == null || !source.equals(metadata.source())
                    || !access.tenantId().equals(metadata.tenantId())) throw integrity();
            return metadata;
        } catch (WorldDraftCandidateException failure) { throw failure; }
        catch (RuntimeException failure) { throw unauthorized(); }
    }

    static WorldDraftSourceAuthority.SourcePayload read(WorldDraftSourceRepository repository,
                                                        WorldDraftSourceAuthority.SourceMetadata metadata,
                                                        WorldDraftCandidateService.Access access,
                                                        WorldDraftSourceRef.Kind expected) {
        if (metadata == null || metadata.source() == null || metadata.source().kind() != expected
                || access == null || !access.tenantId().equals(metadata.tenantId())) throw unauthorized();
        try {
            WorldDraftSourceAuthority.SourcePayload payload = repository.read(metadata, access);
            if (payload == null) throw integrity();
            return payload;
        } catch (WorldDraftCandidateException failure) { throw failure; }
        catch (RuntimeException failure) { throw new WorldDraftCandidateException(
                WorldDraftCandidateException.Code.SOURCE_READ_FAILED); }
    }

    private static WorldDraftCandidateException unauthorized() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED);
    }
    private static WorldDraftCandidateException integrity() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.SOURCE_INTEGRITY);
    }
    private static WorldDraftCandidateException invalid() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.INVALID_INPUT);
    }
}
