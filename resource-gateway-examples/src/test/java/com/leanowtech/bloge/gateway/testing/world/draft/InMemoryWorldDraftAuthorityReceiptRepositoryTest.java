package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryWorldDraftAuthorityReceiptRepositoryTest {
    private static final String TENANT = "tenant-a";
    private static final String CANDIDATE = "candidate-a";
    private static final WorldDraftCandidateService.Access ACCESS =
            new WorldDraftCandidateService.Access(
                    TENANT, WorldDraftCandidateService.PURPOSE, "reviewer-a", "correlation-a");

    @Test
    void savesAndReadsReceiptsUsingAccessTenantWhenCandidateIdDiffers() {
        InMemoryWorldDraftAuthorityReceiptRepository repository =
                new InMemoryWorldDraftAuthorityReceiptRepository();
        WorldDraftApproval approval = new WorldDraftApproval(CANDIDATE, 2,
                fp("source"), fp("schema"), fp("policy"), "ticket-a", "reviewer-a",
                Instant.parse("2026-08-27T00:00:00Z"));
        WorldDraftPublicationReceipt publication = new WorldDraftPublicationReceipt(
                CANDIDATE, 4, fp("materialization"), "publish-ticket-a");

        repository.saveApproval(approval, ACCESS);
        repository.savePublication(publication, ACCESS);

        assertThat(repository.findApproval(TENANT, CANDIDATE, approval.fingerprint(), ACCESS))
                .contains(approval);
        assertThat(repository.findPublication(TENANT, CANDIDATE,
                InMemoryWorldDraftAssetRepository.receiptFingerprint(publication), ACCESS))
                .contains(publication);
    }

    private static String fp(String seed) {
        return VisualBundleFingerprint.fromMaterial(Map.of("seed", seed));
    }
}
