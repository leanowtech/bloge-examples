package com.leanowtech.bloge.gateway.testing.world.draft;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Local/test receipt store; database mode must supply the durable implementation. */
public final class InMemoryWorldDraftAuthorityReceiptRepository implements WorldDraftAuthorityReceiptRepository {
    private final ConcurrentHashMap<Key, Object> receipts = new ConcurrentHashMap<>();

    @Override public void saveApproval(WorldDraftApproval receipt, WorldDraftCandidateService.Access access) {
        authorize(access == null ? null : access.tenantId(), access);
        receipts.putIfAbsent(new Key(access.tenantId(), receipt.candidateId(), "APPROVAL", receipt.fingerprint()), receipt);
    }
    @Override public Optional<WorldDraftApproval> findApproval(String tenantId, String candidateId,
                                                                String fingerprint,
                                                                WorldDraftCandidateService.Access access) {
        authorize(tenantId, access);
        Object value = receipts.get(new Key(tenantId, candidateId, "APPROVAL", fingerprint));
        return value instanceof WorldDraftApproval approval ? Optional.of(approval) : Optional.empty();
    }
    @Override public void savePublication(WorldDraftPublicationReceipt receipt, WorldDraftCandidateService.Access access) {
        authorize(access == null ? null : access.tenantId(), access);
        receipts.putIfAbsent(new Key(access.tenantId(), receipt.candidateId(), "PUBLICATION",
                InMemoryWorldDraftAssetRepository.receiptFingerprint(receipt)), receipt);
    }
    @Override public Optional<WorldDraftPublicationReceipt> findPublication(String tenantId, String candidateId,
                                                                             String fingerprint,
                                                                             WorldDraftCandidateService.Access access) {
        authorize(tenantId, access);
        Object value = receipts.get(new Key(tenantId, candidateId, "PUBLICATION", fingerprint));
        return value instanceof WorldDraftPublicationReceipt publication ? Optional.of(publication) : Optional.empty();
    }

    void removePublication(WorldDraftPublicationReceipt receipt, WorldDraftCandidateService.Access access) {
        authorize(access == null ? null : access.tenantId(), access);
        if (receipt == null) return;
        receipts.remove(new Key(access.tenantId(), receipt.candidateId(), "PUBLICATION",
                InMemoryWorldDraftAssetRepository.receiptFingerprint(receipt)), receipt);
    }
    private static void authorize(String tenant, WorldDraftCandidateService.Access access) {
        if (tenant == null || access == null || !tenant.equals(access.tenantId())) throw new WorldDraftCandidateException(
                WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED);
    }
    private record Key(String tenant, String candidate, String kind, String fingerprint) { }
}
