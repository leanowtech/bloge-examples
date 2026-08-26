package com.leanowtech.bloge.gateway.testing.world.draft;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Local/test asset repository with durable-port semantics. */
public final class InMemoryWorldDraftAssetRepository implements WorldDraftAssetRepository {
    private final ConcurrentHashMap<Key, StoredAsset> assets = new ConcurrentHashMap<>();

    @Override public StoredAsset saveDraft(WorldDraftMaterializer.MaterializedDraft draft,
                                            WorldDraftCandidateService.Access access) {
        authorize(draft == null ? null : draft.candidate().tenantId(), access);
        StoredAsset candidate = new StoredAsset(draft);
        Key key = new Key(candidate.tenantId(), candidate.candidateId(), candidate.materializationFingerprint());
        StoredAsset existing = assets.putIfAbsent(key, candidate);
        if (existing == null) return candidate;
        if (!same(existing, candidate)) throw conflict();
        return existing;
    }

    @Override public Optional<StoredAsset> find(String tenantId, String candidateId,
                                                 String materializationFingerprint,
                                                 WorldDraftCandidateService.Access access) {
        authorize(tenantId, access);
        if (candidateId == null || materializationFingerprint == null) return Optional.empty();
        return Optional.ofNullable(assets.get(new Key(tenantId, candidateId, materializationFingerprint)));
    }

    @Override public StoredAsset publish(StoredAsset asset, WorldDraftCandidate candidate,
                                         WorldDraftPublicationReceipt receipt,
                                         WorldDraftCandidateService.Access access) {
        authorize(asset == null ? null : asset.tenantId(), access);
        if (asset == null || candidate == null || receipt == null || asset.published()
                || !candidate.candidateId().equals(asset.candidateId())
                || !candidate.tenantId().equals(asset.tenantId())
                || !candidate.materializationFingerprint().equals(asset.materializationFingerprint())
                || receipt.candidateRevision() != candidate.revision()
                || !receipt.materializationFingerprint().equals(asset.materializationFingerprint())) {
            throw conflict();
        }
        StoredAsset published = asset.asPublished(receiptFingerprint(receipt));
        Key key = new Key(asset.tenantId(), asset.candidateId(), asset.materializationFingerprint());
        if (!assets.replace(key, asset, published)) throw conflict();
        return published;
    }

    void restoreDraft(StoredAsset draft, WorldDraftPublicationReceipt receipt,
                      WorldDraftCandidateService.Access access) {
        authorize(draft == null ? null : draft.tenantId(), access);
        if (draft == null || receipt == null) throw conflict();
        Key key = new Key(draft.tenantId(), draft.candidateId(), draft.materializationFingerprint());
        StoredAsset published = draft.asPublished(receiptFingerprint(receipt));
        if (!assets.replace(key, published, draft)) throw conflict();
    }

    private static boolean same(StoredAsset left, StoredAsset right) {
        return left.worldModel().fingerprint().equals(right.worldModel().fingerprint())
                && left.rule().fingerprint().equals(right.rule().fingerprint())
                && left.provenance().equals(right.provenance());
    }
    static String receiptFingerprint(WorldDraftPublicationReceipt receipt) {
        return com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint.fromMaterial(
                java.util.Map.of("candidateId", receipt.candidateId(), "revision", receipt.candidateRevision(),
                        "materializationFingerprint", receipt.materializationFingerprint(), "ticket", receipt.ticket()));
    }
    private static void authorize(String tenant, WorldDraftCandidateService.Access access) {
        if (tenant == null || access == null || !tenant.equals(access.tenantId())) throw new WorldDraftCandidateException(
                WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED);
    }
    private static WorldDraftCandidateException conflict() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.CAS_CONFLICT);
    }
    private record Key(String tenant, String candidate, String fingerprint) { }
}
