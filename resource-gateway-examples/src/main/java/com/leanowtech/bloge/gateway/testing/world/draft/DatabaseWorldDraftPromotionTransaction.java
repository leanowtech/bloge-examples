package com.leanowtech.bloge.gateway.testing.world.draft;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/** Database promotion boundary; every publication side effect participates in one JDBC transaction. */
public final class DatabaseWorldDraftPromotionTransaction implements WorldDraftPromotionTransaction {
    private final WorldDraftCandidateRepository candidates;
    private final WorldDraftAssetRepository assets;
    private final WorldDraftAuthorityReceiptRepository receipts;
    private final WorldDraftPublicationAuthority publicationAuthority;
    private final WorldDraftRedactedPayloadVault vault;
    private final TransactionTemplate transactions;

    public DatabaseWorldDraftPromotionTransaction(DataSource dataSource,
                                                  WorldDraftCandidateRepository candidates,
                                                  WorldDraftAssetRepository assets,
                                                  WorldDraftAuthorityReceiptRepository receipts,
                                                  WorldDraftPublicationAuthority publicationAuthority) {
        this(dataSource, candidates, assets, receipts, publicationAuthority, null);
    }

    public DatabaseWorldDraftPromotionTransaction(DataSource dataSource,
                                                  WorldDraftCandidateRepository candidates,
                                                  WorldDraftAssetRepository assets,
                                                  WorldDraftAuthorityReceiptRepository receipts,
                                                  WorldDraftPublicationAuthority publicationAuthority,
                                                  WorldDraftRedactedPayloadVault vault) {
        if (dataSource == null || candidates == null || assets == null || receipts == null
                || publicationAuthority == null) throw invalid();
        this.candidates = candidates;
        this.assets = assets;
        this.receipts = receipts;
        this.publicationAuthority = publicationAuthority;
        this.vault = vault;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public WorldDraftCandidate promote(WorldDraftCandidate expected,
                                       WorldDraftCandidateService.Access access) {
        try {
            WorldDraftCandidate result = transactions.execute(status -> promoteInTransaction(expected, access));
            if (result == null) throw invalid();
            return result;
        } catch (WorldDraftCandidateException failure) { throw failure; }
        catch (RuntimeException failure) { throw invalid(); }
    }

    private WorldDraftCandidate promoteInTransaction(WorldDraftCandidate expected,
                                                     WorldDraftCandidateService.Access access) {
        if (expected == null || access == null || !expected.tenantId().equals(access.tenantId())) {
            throw fail(WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED);
        }
        WorldDraftCandidate current = candidates.find(access.tenantId(), expected.candidateId())
                .orElseThrow(() -> fail(WorldDraftCandidateException.Code.CANDIDATE_NOT_FOUND));
        if (!current.equals(expected)) throw fail(WorldDraftCandidateException.Code.CAS_CONFLICT);
        WorldDraftCandidateGuards.require(current, WorldDraftState.MATERIALIZED_DRAFT);
        WorldDraftAssetRepository.StoredAsset asset = assets.find(current.tenantId(), current.candidateId(),
                current.materializationFingerprint(), access).orElseThrow(() ->
                fail(WorldDraftCandidateException.Code.PUBLICATION_INVALID));
        if (asset.published() || !asset.provenance().matches(current, asset.rule())) {
            throw fail(WorldDraftCandidateException.Code.PUBLICATION_INVALID);
        }
        WorldDraftPublicationReceipt receipt = issue(current, access);
        validateReceipt(current, receipt);
        String receiptFingerprint = InMemoryWorldDraftAssetRepository.receiptFingerprint(receipt);
        receipts.savePublication(receipt, access);
        WorldDraftPublicationReceipt persisted = receipts.findPublication(current.tenantId(),
                current.candidateId(), receiptFingerprint, access).orElseThrow(() ->
                fail(WorldDraftCandidateException.Code.PUBLICATION_INVALID));
        if (!persisted.equals(receipt)) throw fail(WorldDraftCandidateException.Code.PUBLICATION_INVALID);
        WorldDraftAssetRepository.StoredAsset published = assets.publish(asset, current, receipt, access);
        if (published == null || !published.published()) throw fail(WorldDraftCandidateException.Code.PUBLICATION_INVALID);
        WorldDraftRedactedPayloadVault.PublishedBinding binding = null;
        boolean pinned = false;
        try {
            if (vault != null) {
                if (published.rule().redactedPayloadRef() == null
                        || current.redactedPayloadRef() == null
                        || !current.redactedPayloadRef().equals(published.rule().redactedPayloadRef())) throw fail(
                        WorldDraftCandidateException.Code.PUBLICATION_INVALID);
                binding = new WorldDraftRedactedPayloadVault.PublishedBinding(
                        current.tenantId(), current.candidateId(), current.redactedPayloadRef().artifactRevision(),
                        published.worldModel().fingerprint(), published.rule().fingerprint(), receiptFingerprint);
                vault.pin(current.redactedPayloadRef(), binding, access);
                pinned = true;
            }
            WorldDraftCandidate replacement = current.next(WorldDraftState.PUBLISHED,
                    current.approvalFingerprint(), current.materializationFingerprint(), current.redactedPayloadRef(),
                    current.redactionReportFingerprint(), current.redactionReport());
            if (!candidates.compareAndSet(current, replacement)) throw fail(WorldDraftCandidateException.Code.CAS_CONFLICT);
            return replacement;
        } catch (RuntimeException failure) {
            if (pinned && binding != null && vault != null) vault.unpin(current.redactedPayloadRef(), binding, access);
            throw failure;
        }
    }

    private WorldDraftPublicationReceipt issue(WorldDraftCandidate candidate,
                                               WorldDraftCandidateService.Access access) {
        try {
            WorldDraftPublicationReceipt receipt = publicationAuthority.issue(candidate, access);
            if (receipt == null) throw invalid();
            return receipt;
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
