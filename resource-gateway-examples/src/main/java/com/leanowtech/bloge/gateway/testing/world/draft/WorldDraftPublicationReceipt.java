package com.leanowtech.bloge.gateway.testing.world.draft;

/** Explicit external authorization required to mark a materialized draft as published. */
public record WorldDraftPublicationReceipt(String candidateId, long candidateRevision,
                                           String materializationFingerprint, String ticket) {
    public WorldDraftPublicationReceipt {
        if (candidateId == null || candidateId.isBlank() || candidateRevision < 1
                || !fp(materializationFingerprint) || text(ticket).isBlank()) {
            throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.PUBLICATION_INVALID);
        }
        candidateId = candidateId.trim();
        ticket = ticket.trim();
    }

    private static String text(String value) {
        if (value == null || value.length() > 512
                || value.chars().anyMatch(Character::isISOControl)) throw new WorldDraftCandidateException(
                WorldDraftCandidateException.Code.PUBLICATION_INVALID);
        return value;
    }
    private static boolean fp(String value) { return value != null && value.matches("sha256:[a-f0-9]{64}"); }
}
