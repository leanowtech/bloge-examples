package com.leanowtech.bloge.gateway.testing.world.draft;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Single-process promotion transaction used by isolated examples and tests.
 * The lock serializes the complete promotion and the compensating actions make injected failures
 * retryable without leaving a published asset behind.
 */
public final class InMemoryWorldDraftPromotionTransaction implements WorldDraftPromotionTransaction {
    enum FailurePoint {
        AFTER_RECEIPT_PERSISTED,
        AFTER_ASSET_PUBLISHED,
        AFTER_BEHAVIOR_PINNED,
        BEFORE_CANDIDATE_CAS
    }

    @FunctionalInterface
    interface FailureInjector {
        void after(FailurePoint point);
    }

    private final WorldDraftCandidateRepository candidates;
    private final InMemoryWorldDraftAssetRepository assets;
    private final InMemoryWorldDraftAuthorityReceiptRepository receipts;
    private final WorldDraftPublicationAuthority publicationAuthority;
    private final WorldDraftRedactedPayloadVault vault;
    private final FailureInjector failureInjector;
    private final ReentrantLock lock = new ReentrantLock();

    public InMemoryWorldDraftPromotionTransaction(WorldDraftCandidateRepository candidates,
                                                  InMemoryWorldDraftAssetRepository assets,
                                                  InMemoryWorldDraftAuthorityReceiptRepository receipts,
                                                  WorldDraftPublicationAuthority publicationAuthority) {
        this(candidates, assets, receipts, publicationAuthority, null, point -> { });
    }

    public InMemoryWorldDraftPromotionTransaction(WorldDraftCandidateRepository candidates,
                                                  InMemoryWorldDraftAssetRepository assets,
                                                  InMemoryWorldDraftAuthorityReceiptRepository receipts,
                                                  WorldDraftPublicationAuthority publicationAuthority,
                                                  WorldDraftRedactedPayloadVault vault) {
        this(candidates, assets, receipts, publicationAuthority, vault, point -> { });
    }

    InMemoryWorldDraftPromotionTransaction(WorldDraftCandidateRepository candidates,
                                           InMemoryWorldDraftAssetRepository assets,
                                           InMemoryWorldDraftAuthorityReceiptRepository receipts,
                                           WorldDraftPublicationAuthority publicationAuthority,
                                           FailureInjector failureInjector) {
        this(candidates, assets, receipts, publicationAuthority, null, failureInjector);
    }

    InMemoryWorldDraftPromotionTransaction(WorldDraftCandidateRepository candidates,
                                           InMemoryWorldDraftAssetRepository assets,
                                           InMemoryWorldDraftAuthorityReceiptRepository receipts,
                                           WorldDraftPublicationAuthority publicationAuthority,
                                           WorldDraftRedactedPayloadVault vault,
                                           FailureInjector failureInjector) {
        if (candidates == null || assets == null || receipts == null || publicationAuthority == null
                || failureInjector == null) throw invalid();
        this.candidates = candidates;
        this.assets = assets;
        this.receipts = receipts;
        this.publicationAuthority = publicationAuthority;
        this.vault = vault;
        this.failureInjector = failureInjector;
    }

    @Override
    public WorldDraftCandidate promote(WorldDraftCandidate expected,
                                       WorldDraftCandidateService.Access access) {
        lock.lock();
        boolean receiptMayHaveBeenWritten = false;
        boolean assetMayHaveBeenPublished = false;
        WorldDraftAssetRepository.StoredAsset asset = null;
        WorldDraftPublicationReceipt receipt = null;
        boolean receiptPreexisted = false;
        WorldDraftRedactedPayloadVault.PublishedBinding binding = null;
        boolean behaviorMayHaveBeenPinned = false;
        try {
            requireExpected(expected, access);
            WorldDraftCandidate current = candidates.find(access.tenantId(), expected.candidateId())
                    .orElseThrow(() -> fail(WorldDraftCandidateException.Code.CANDIDATE_NOT_FOUND));
            if (!current.equals(expected)) throw fail(WorldDraftCandidateException.Code.CAS_CONFLICT);
            WorldDraftCandidateGuards.require(current, WorldDraftState.MATERIALIZED_DRAFT);
            asset = assets.find(current.tenantId(), current.candidateId(),
                    current.materializationFingerprint(), access).orElseThrow(() ->
                    fail(WorldDraftCandidateException.Code.PUBLICATION_INVALID));
            if (asset.published() || !asset.provenance().matches(current, asset.rule())) {
                throw fail(WorldDraftCandidateException.Code.PUBLICATION_INVALID);
            }
            receipt = issue(current, access);
            validateReceipt(current, receipt);
            String receiptFingerprint = InMemoryWorldDraftAssetRepository.receiptFingerprint(receipt);
            receiptPreexisted = receipts.findPublication(current.tenantId(), current.candidateId(),
                    receiptFingerprint, access).isPresent();
            receiptMayHaveBeenWritten = true;
            receipts.savePublication(receipt, access);
            failureInjector.after(FailurePoint.AFTER_RECEIPT_PERSISTED);
            WorldDraftPublicationReceipt persisted = receipts.findPublication(current.tenantId(),
                    current.candidateId(), receiptFingerprint, access).orElseThrow(() ->
                    fail(WorldDraftCandidateException.Code.PUBLICATION_INVALID));
            if (!persisted.equals(receipt)) throw fail(WorldDraftCandidateException.Code.PUBLICATION_INVALID);

            assetMayHaveBeenPublished = true;
            WorldDraftAssetRepository.StoredAsset published = assets.publish(asset, current, receipt, access);
            if (published == null || !published.published()) throw fail(WorldDraftCandidateException.Code.PUBLICATION_INVALID);
            failureInjector.after(FailurePoint.AFTER_ASSET_PUBLISHED);

            if (vault != null) {
                binding = publishedBinding(current, published, receiptFingerprint);
                vault.pin(current.redactedPayloadRef(), binding, access);
                behaviorMayHaveBeenPinned = true;
                failureInjector.after(FailurePoint.AFTER_BEHAVIOR_PINNED);
            }

            WorldDraftCandidate replacement = current.next(WorldDraftState.PUBLISHED,
                    current.approvalFingerprint(), current.materializationFingerprint(), current.redactedPayloadRef(),
                    current.redactionReportFingerprint(), current.redactionReport());
            failureInjector.after(FailurePoint.BEFORE_CANDIDATE_CAS);
            if (!candidates.compareAndSet(current, replacement)) throw fail(WorldDraftCandidateException.Code.CAS_CONFLICT);
            return replacement;
        } catch (WorldDraftCandidateException failure) {
            rollback(asset, receipt, access, receiptMayHaveBeenWritten, receiptPreexisted,
                    assetMayHaveBeenPublished, binding, behaviorMayHaveBeenPinned);
            throw failure;
        } catch (RuntimeException failure) {
            rollback(asset, receipt, access, receiptMayHaveBeenWritten, receiptPreexisted,
                    assetMayHaveBeenPublished, binding, behaviorMayHaveBeenPinned);
            throw fail(WorldDraftCandidateException.Code.PUBLICATION_INVALID);
        } finally {
            lock.unlock();
        }
    }

    private void rollback(WorldDraftAssetRepository.StoredAsset asset,
                          WorldDraftPublicationReceipt receipt,
                          WorldDraftCandidateService.Access access,
                          boolean receiptMayHaveBeenWritten,
                          boolean receiptPreexisted,
                          boolean assetMayHaveBeenPublished,
                          WorldDraftRedactedPayloadVault.PublishedBinding binding,
                          boolean behaviorMayHaveBeenPinned) {
        if (behaviorMayHaveBeenPinned && binding != null && receipt != null && asset != null && vault != null) {
            vault.unpin(asset.rule().redactedPayloadRef(), binding, access);
        }
        if (assetMayHaveBeenPublished && asset != null && receipt != null) {
            assets.restoreDraft(asset, receipt, access);
        }
        if (receiptMayHaveBeenWritten && !receiptPreexisted && receipt != null) {
            receipts.removePublication(receipt, access);
        }
    }

    private static WorldDraftRedactedPayloadVault.PublishedBinding publishedBinding(
            WorldDraftCandidate candidate, WorldDraftAssetRepository.StoredAsset asset,
            String receiptFingerprint) {
        if (candidate.redactedPayloadRef() == null || asset.rule().redactedPayloadRef() == null
                || !candidate.redactedPayloadRef().equals(asset.rule().redactedPayloadRef())) throw fail(
                WorldDraftCandidateException.Code.PUBLICATION_INVALID);
        return new WorldDraftRedactedPayloadVault.PublishedBinding(candidate.tenantId(), candidate.candidateId(),
                candidate.redactedPayloadRef().artifactRevision(), asset.worldModel().fingerprint(),
                asset.rule().fingerprint(), receiptFingerprint);
    }

    private static void requireExpected(WorldDraftCandidate expected,
                                        WorldDraftCandidateService.Access access) {
        if (expected == null || access == null || !expected.tenantId().equals(access.tenantId())) {
            throw fail(WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED);
        }
    }

    private WorldDraftPublicationReceipt issue(WorldDraftCandidate candidate,
                                               WorldDraftCandidateService.Access access) {
        try {
            WorldDraftPublicationReceipt value = publicationAuthority.issue(candidate, access);
            if (value == null) throw invalid();
            return value;
        } catch (WorldDraftCandidateException failure) { throw failure; }
        catch (RuntimeException failure) { throw fail(WorldDraftCandidateException.Code.PUBLICATION_INVALID); }
    }

    private static void validateReceipt(WorldDraftCandidate candidate,
                                        WorldDraftPublicationReceipt receipt) {
        if (!receipt.candidateId().equals(candidate.candidateId())
                || receipt.candidateRevision() != candidate.revision()
                || !receipt.materializationFingerprint().equals(candidate.materializationFingerprint())) {
            throw fail(WorldDraftCandidateException.Code.PUBLICATION_INVALID);
        }
    }

    private static WorldDraftCandidateException invalid() {
        return fail(WorldDraftCandidateException.Code.PUBLICATION_INVALID);
    }

    private static WorldDraftCandidateException fail(WorldDraftCandidateException.Code code) {
        return new WorldDraftCandidateException(code);
    }
}
