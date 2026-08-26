package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InMemoryWorldDraftPromotionTransactionTest {
    @Test
    void everyFailureAfterMutationRollsBackAndCanBeRetried() {
        for (InMemoryWorldDraftPromotionTransaction.FailurePoint point :
                InMemoryWorldDraftPromotionTransaction.FailurePoint.values()) {
            PromotionFixture fixture = fixture(point);

            assertThatThrownBy(() -> fixture.transaction.promote(fixture.current, fixture.access))
                    .isInstanceOf(WorldDraftCandidateException.class)
                    .extracting(error -> ((WorldDraftCandidateException) error).code())
                    .isEqualTo(WorldDraftCandidateException.Code.PUBLICATION_INVALID);
            assertThat(fixture.candidates.find(fixture.access.tenantId(), fixture.current.candidateId()))
                    .contains(fixture.current);
            assertThat(fixture.assets.find(fixture.access.tenantId(), fixture.current.candidateId(),
                    fixture.current.materializationFingerprint(), fixture.access)).get()
                    .extracting(WorldDraftAssetRepository.StoredAsset::published).isEqualTo(false);
            assertThat(fixture.receipts.findPublication(fixture.access.tenantId(), fixture.current.candidateId(),
                    fixture.receiptFingerprint, fixture.access)).isEmpty();
            WorldDraftAssetRepository.StoredAsset draft = fixture.assets.find(fixture.access.tenantId(),
                    fixture.current.candidateId(), fixture.current.materializationFingerprint(), fixture.access).orElseThrow();
            WorldDraftRedactedPayloadVault.PublishedBinding binding = new WorldDraftRedactedPayloadVault.PublishedBinding(
                    fixture.access.tenantId(), fixture.current.candidateId(),
                    fixture.current.redactedPayloadRef().artifactRevision(), draft.worldModel().fingerprint(),
                    draft.rule().fingerprint(), fixture.receiptFingerprint);
            assertThat(fixture.vault.readPublished(fixture.current.redactedPayloadRef(), binding,
                    fixture.access)).isEmpty();

            WorldDraftCandidate published = fixture.transaction.promote(fixture.current, fixture.access);
            assertThat(published.state()).isEqualTo(WorldDraftState.PUBLISHED);
            assertThat(fixture.candidates.find(fixture.access.tenantId(), fixture.current.candidateId()))
                    .contains(published);
            assertThat(fixture.assets.find(fixture.access.tenantId(), fixture.current.candidateId(),
                    fixture.current.materializationFingerprint(), fixture.access)).get()
                    .extracting(WorldDraftAssetRepository.StoredAsset::published).isEqualTo(true);
            assertThat(fixture.vault.readPublished(fixture.current.redactedPayloadRef(), binding,
                    fixture.access)).isPresent();
        }
    }

    @Test
    void concurrentPromotionHasOneCandidateWinnerAndNoTornPublishedState() throws Exception {
        PromotionFixture fixture = fixture(null);
        Thread first = new Thread(() -> promoteIgnoringFailure(fixture));
        Thread second = new Thread(() -> promoteIgnoringFailure(fixture));
        first.start();
        second.start();
        first.join();
        second.join();

        assertThat(fixture.candidates.find(fixture.access.tenantId(), fixture.current.candidateId()))
                .get().extracting(WorldDraftCandidate::state).isEqualTo(WorldDraftState.PUBLISHED);
        assertThat(fixture.assets.find(fixture.access.tenantId(), fixture.current.candidateId(),
                fixture.current.materializationFingerprint(), fixture.access)).get()
                .extracting(WorldDraftAssetRepository.StoredAsset::published).isEqualTo(true);
    }

    private static void promoteIgnoringFailure(PromotionFixture fixture) {
        try {
            fixture.transaction.promote(fixture.current, fixture.access);
        } catch (WorldDraftCandidateException ignored) {
            // The second invocation observes the already-promoted head and fails closed.
        }
    }

    private static PromotionFixture fixture(InMemoryWorldDraftPromotionTransaction.FailurePoint failPoint) {
        WorldDraftCandidateService.Access access = WorldDraftTestSupport.ACCESS;
        WorldDraftSourceRef source = WorldDraftTestSupport.source(WorldDraftSourceRef.Kind.GOLDEN_CAPTURE,
                access.tenantId(), "promotion-source");
        WorldDraftRedactedPayload payload = new WorldDraftRedactedPayload(
                Map.of("safe", "request"), Map.of("result", "response"));
        WorldDraftRedactedPayloadRef payloadRef = WorldDraftRedactedPayloadRef.of(
                access.tenantId(), "promotion-candidate", 1, payload);
        WorldDraftRule rule = new WorldDraftRule(WorldDraftTestSupport.fp("schema"),
                payloadRef.requestFingerprint(), payloadRef.responseFingerprint(), null, payloadRef);
        WorldDraftCandidate approved = new WorldDraftCandidate("promotion-candidate", 3,
                WorldDraftState.APPROVED, access.tenantId(), source,
                WorldDraftTestSupport.fp("metadata"), WorldDraftTestSupport.fp("schema"),
                WorldDraftTestSupport.fp("policy"), WorldDraftTestSupport.fp("request"),
                WorldDraftTestSupport.fp("response"), payloadRef, WorldDraftTestSupport.fp("report"),
                WorldDraftRedactionReport.notProcessed(), WorldDraftTestSupport.fp("approval"), rule.fingerprint());
        WorldDraftCandidate current = approved.next(WorldDraftState.MATERIALIZED_DRAFT,
                approved.approvalFingerprint(), rule.fingerprint(), payloadRef,
                approved.redactionReportFingerprint(), approved.redactionReport());
        ResourceWorldModel world = WorldDraftTestSupport.world("promotion-world", 2);
        WorldDraftMaterializer.MaterializedDraft draft = new WorldDraftMaterializer.MaterializedDraft(
                approved, world, rule, false);
        InMemoryWorldDraftAssetRepository assets = new InMemoryWorldDraftAssetRepository();
        assets.saveDraft(draft, access);
        InMemoryWorldDraftAuthorityReceiptRepository receipts =
                new InMemoryWorldDraftAuthorityReceiptRepository();
        InMemoryWorldDraftRedactedPayloadVault vault = new InMemoryWorldDraftRedactedPayloadVault();
        vault.put(payloadRef, payload, access);
        AtomicReference<WorldDraftCandidate> head = new AtomicReference<>(current);
        WorldDraftCandidateRepository candidates = new WorldDraftCandidateRepository() {
            @Override public WorldDraftCandidate create(WorldDraftCandidate candidate) {
                return head.updateAndGet(previous -> previous == null ? candidate : previous);
            }

            @Override public Optional<WorldDraftCandidate> find(String tenantId, String candidateId) {
                WorldDraftCandidate value = head.get();
                return value != null && value.tenantId().equals(tenantId)
                        && value.candidateId().equals(candidateId) ? Optional.of(value) : Optional.empty();
            }

            @Override public boolean compareAndSet(WorldDraftCandidate expected, WorldDraftCandidate replacement) {
                return head.compareAndSet(expected, replacement);
            }
        };
        WorldDraftPublicationAuthority authority = (candidate, ignored) -> new WorldDraftPublicationReceipt(
                candidate.candidateId(), candidate.revision(), candidate.materializationFingerprint(), "publish");
        AtomicReference<InMemoryWorldDraftPromotionTransaction.FailurePoint> failure =
                new AtomicReference<>(failPoint);
        InMemoryWorldDraftPromotionTransaction.FailureInjector injector = point -> {
            if (failure.compareAndSet(point, null)) throw new IllegalStateException("injected");
        };
        InMemoryWorldDraftPromotionTransaction transaction = new InMemoryWorldDraftPromotionTransaction(
                candidates, assets, receipts, authority, vault, injector);
        String receiptFingerprint = InMemoryWorldDraftAssetRepository.receiptFingerprint(
                new WorldDraftPublicationReceipt(current.candidateId(), current.revision(),
                        current.materializationFingerprint(), "publish"));
        return new PromotionFixture(access, current, candidates, assets, receipts, vault, transaction, receiptFingerprint);
    }

    private record PromotionFixture(WorldDraftCandidateService.Access access,
                                    WorldDraftCandidate current,
                                    WorldDraftCandidateRepository candidates,
                                    InMemoryWorldDraftAssetRepository assets,
                                    InMemoryWorldDraftAuthorityReceiptRepository receipts,
                                    InMemoryWorldDraftRedactedPayloadVault vault,
                                    InMemoryWorldDraftPromotionTransaction transaction,
                                    String receiptFingerprint) { }
}
