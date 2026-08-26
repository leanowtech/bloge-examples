package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Instant;
import java.util.Map;

/** Exact human approval receipt for one candidate revision. */
public record WorldDraftApproval(
        String candidateId,
        long candidateRevision,
        String sourceFingerprint,
        String schemaFingerprint,
        String redactionPolicyFingerprint,
        String reviewTicket,
        String reviewer,
        Instant approvedAt
) {
    public WorldDraftApproval {
        if (candidateId == null || candidateId.isBlank() || candidateRevision < 1
                || !fp(sourceFingerprint) || !fp(schemaFingerprint) || !fp(redactionPolicyFingerprint)
                || text(reviewTicket).isBlank() || text(reviewer).isBlank() || approvedAt == null) {
            throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.APPROVAL_INVALID);
        }
        candidateId = candidateId.trim();
        reviewTicket = reviewTicket.trim();
        reviewer = reviewer.trim();
    }

    public String fingerprint() {
        return VisualBundleFingerprint.fromMaterial(Map.of("candidateId", candidateId,
                "candidateRevision", candidateRevision, "sourceFingerprint", sourceFingerprint,
                "schemaFingerprint", schemaFingerprint,
                "redactionPolicyFingerprint", redactionPolicyFingerprint,
                "reviewTicket", reviewTicket, "reviewer", reviewer,
                "approvedAt", approvedAt.toString()));
    }

    private static String text(String value) {
        if (value == null || value.isBlank() || value.length() > 512
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.APPROVAL_INVALID);
        }
        return value;
    }

    private static boolean fp(String value) { return value != null && value.matches("sha256:[a-f0-9]{64}"); }
}
