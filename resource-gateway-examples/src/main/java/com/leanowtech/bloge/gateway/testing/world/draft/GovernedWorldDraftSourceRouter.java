package com.leanowtech.bloge.gateway.testing.world.draft;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Routes each governed source kind to its dedicated server-owned repository adapter. */
public final class GovernedWorldDraftSourceRouter implements WorldDraftSourceAuthority {
    private final Map<WorldDraftSourceRef.Kind, WorldDraftSourceAdapter> adapters;

    public GovernedWorldDraftSourceRouter(List<WorldDraftSourceAdapter> adapters) {
        if (adapters == null || adapters.size() != WorldDraftSourceRef.Kind.values().length) throw invalid();
        EnumMap<WorldDraftSourceRef.Kind, WorldDraftSourceAdapter> indexed =
                new EnumMap<>(WorldDraftSourceRef.Kind.class);
        for (WorldDraftSourceAdapter adapter : adapters) {
            if (adapter == null || adapter.kind() == null || indexed.put(adapter.kind(), adapter) != null) throw invalid();
        }
        if (indexed.size() != WorldDraftSourceRef.Kind.values().length) throw invalid();
        this.adapters = Map.copyOf(indexed);
    }

    @Override
    public SourceMetadata inspect(WorldDraftSourceRef source, WorldDraftCandidateService.Access access) {
        WorldDraftSourceAdapter adapter = adapter(source);
        SourceMetadata metadata = adapter.inspect(source, access);
        if (metadata == null || !source.equals(metadata.source()) || !source.tenantId().equals(metadata.tenantId())) throw invalid();
        return metadata;
    }

    @Override
    public SourcePayload read(SourceMetadata metadata, WorldDraftCandidateService.Access access) {
        if (metadata == null || metadata.source() == null) throw invalid();
        WorldDraftSourceAdapter adapter = adapter(metadata.source());
        SourcePayload payload = adapter.read(metadata, access);
        if (payload == null) throw invalid();
        return payload;
    }

    private WorldDraftSourceAdapter adapter(WorldDraftSourceRef source) {
        if (source == null || adapters.get(source.kind()) == null) throw invalid();
        return adapters.get(source.kind());
    }

    private static WorldDraftCandidateException invalid() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED);
    }
}
