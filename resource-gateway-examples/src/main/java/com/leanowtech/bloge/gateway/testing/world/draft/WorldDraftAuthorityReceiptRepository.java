package com.leanowtech.bloge.gateway.testing.world.draft;

import java.util.Optional;

/** Durable server-receipt boundary; candidate rows retain only receipt fingerprints. */
public interface WorldDraftAuthorityReceiptRepository {
    void saveApproval(WorldDraftApproval receipt, WorldDraftCandidateService.Access access);
    Optional<WorldDraftApproval> findApproval(String tenantId, String candidateId,
                                              String approvalFingerprint,
                                              WorldDraftCandidateService.Access access);
    void savePublication(WorldDraftPublicationReceipt receipt, WorldDraftCandidateService.Access access);
    Optional<WorldDraftPublicationReceipt> findPublication(String tenantId, String candidateId,
                                                           String publicationFingerprint,
                                                           WorldDraftCandidateService.Access access);
}
