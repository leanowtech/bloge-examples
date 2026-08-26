package com.leanowtech.bloge.gateway.testing.world.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.world.WorldFragmentTestKit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-owned resolver for published world behavior. The catalog carries only the reference;
 * this boundary authorizes the pinned value and injects it into the generated fragment context.
 */
public final class WorldDraftPublishedBehaviorRuntime {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final WorldDraftAssetRepository assets;
    private final WorldDraftRedactedPayloadVault vault;
    private final WorldFragmentTestKit fragments;

    public WorldDraftPublishedBehaviorRuntime(WorldDraftAssetRepository assets,
                                              WorldDraftRedactedPayloadVault vault,
                                              WorldFragmentTestKit fragments) {
        if (assets == null || vault == null || fragments == null) throw invalid();
        this.assets = assets;
        this.vault = vault;
        this.fragments = fragments;
    }

    /** Reloads an asset by its tenant-scoped durable identity before executing it. */
    public Object execute(String candidateId, String materializationFingerprint, Object request,
                          WorldDraftCandidateService.Access access) {
        if (access == null) throw unauthorized();
        WorldDraftAssetRepository.StoredAsset asset = assets.find(access.tenantId(), candidateId,
                materializationFingerprint, access).orElseThrow(WorldDraftPublishedBehaviorRuntime::notFound);
        return execute(asset, request, access);
    }

    /** Executes only a published, payload-free asset projection. */
    public Object execute(WorldDraftAssetRepository.StoredAsset asset, Object request,
                          WorldDraftCandidateService.Access access) {
        if (asset == null || access == null || !asset.published()
                || !asset.tenantId().equals(access.tenantId()) || asset.rule().redactedPayloadRef() == null
                || asset.rule().fragment() == null || !asset.publicationReceiptFingerprint().matches("sha256:[a-f0-9]{64}")) {
            throw notFound();
        }
        try {
            String requestFingerprint = ProtocolFingerprint.of(MAPPER, request);
            if (!asset.rule().inputFingerprint().equals(requestFingerprint)) throw notFound();
            WorldDraftRedactedPayloadRef ref = asset.rule().redactedPayloadRef();
            WorldDraftRedactedPayloadVault.PublishedBinding binding = new WorldDraftRedactedPayloadVault.PublishedBinding(
                    asset.tenantId(), asset.candidateId(), ref.artifactRevision(), asset.worldModel().fingerprint(),
                    asset.rule().fingerprint(), asset.publicationReceiptFingerprint());
            WorldDraftRedactedPayloadVault.StoredPayload payload = vault.readPublished(ref, binding, access)
                    .orElseThrow(WorldDraftPublishedBehaviorRuntime::notFound);
            if (!payload.ref().equals(ref) || !payload.payload().requestFingerprint().equals(asset.rule().inputFingerprint())
                    || !payload.payload().responseFingerprint().equals(asset.rule().responseFingerprint())) throw integrity();
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("__draft_response", payload.response());
            Object executed = fragments.execute(asset.rule().fragment().blogeFragment(), context);
            if (!(executed instanceof Map<?, ?> result)
                    || !asset.rule().responseFingerprint().equals(ProtocolFingerprint.of(MAPPER, result.get("value")))) {
                throw integrity();
            }
            return result.get("value");
        } catch (WorldDraftCandidateException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw integrity();
        }
    }

    private static WorldDraftCandidateException unauthorized() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED);
    }

    private static WorldDraftCandidateException notFound() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.SOURCE_NOT_FOUND);
    }

    private static WorldDraftCandidateException integrity() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.SOURCE_INTEGRITY);
    }

    private static WorldDraftCandidateException invalid() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.MATERIALIZATION_INVALID);
    }
}
