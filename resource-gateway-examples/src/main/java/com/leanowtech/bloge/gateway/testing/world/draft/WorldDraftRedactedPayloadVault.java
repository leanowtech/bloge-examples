package com.leanowtech.bloge.gateway.testing.world.draft;

import java.util.Optional;

/** Authorized, immutable vault for the short-lived redacted values used by materialization. */
public interface WorldDraftRedactedPayloadVault {
    StoredPayload put(WorldDraftRedactedPayloadRef ref, WorldDraftRedactedPayload payload,
                      WorldDraftCandidateService.Access access);

    Optional<StoredPayload> read(WorldDraftRedactedPayloadRef ref, WorldDraftCandidateService.Access access);

    /** Reads only a pinned artifact whose publication binding matches the requested world rule. */
    default Optional<StoredPayload> readPublished(WorldDraftRedactedPayloadRef ref, PublishedBinding binding,
                                                   WorldDraftCandidateService.Access access) {
        return Optional.empty();
    }

    /** Server-owned binding that retains a redacted artifact for a published world rule. */
    record PublishedBinding(String tenantId, String candidateId, long artifactRevision,
                            String worldFingerprint, String ruleFingerprint,
                            String publicationReceiptFingerprint) {
        public PublishedBinding {
            tenantId = text(tenantId);
            candidateId = text(candidateId);
            if (artifactRevision < 1 || !fp(worldFingerprint) || !fp(ruleFingerprint)
                    || !fp(publicationReceiptFingerprint)) throw invalid();
        }

        public void requireMatches(WorldDraftRedactedPayloadRef ref,
                                   WorldDraftCandidateService.Access access) {
            if (ref == null || access == null || !tenantId.equals(access.tenantId())
                    || !tenantId.equals(ref.tenantId()) || !candidateId.equals(ref.candidateId())
                    || artifactRevision != ref.artifactRevision()) throw invalid();
        }

        private static String text(String value) {
            if (value == null || value.isBlank() || value.length() > 255
                    || value.chars().anyMatch(Character::isISOControl)) throw invalid();
            return value.trim();
        }
        private static boolean fp(String value) { return value != null && value.matches("sha256:[a-f0-9]{64}"); }
        private static WorldDraftCandidateException invalid() {
            return new WorldDraftCandidateException(WorldDraftCandidateException.Code.PUBLICATION_INVALID);
        }
    }

    /** Promotes the artifact to retention that ordinary expiry purge cannot remove. */
    default void pin(WorldDraftRedactedPayloadRef ref, PublishedBinding binding,
                     WorldDraftCandidateService.Access access) {
        throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.PUBLICATION_INVALID);
    }

    /** Compensation hook for a failed publication transaction. */
    default void unpin(WorldDraftRedactedPayloadRef ref, PublishedBinding binding,
                       WorldDraftCandidateService.Access access) { }

    /** Explicit revocation; revoked behavior can never be read again. */
    default void revoke(WorldDraftRedactedPayloadRef ref, WorldDraftCandidateService.Access access) {
        throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED);
    }

    final class StoredPayload {
        private final WorldDraftRedactedPayloadRef ref;
        private final WorldDraftRedactedPayload payload;

        public StoredPayload(WorldDraftRedactedPayloadRef ref, WorldDraftRedactedPayload payload) {
            if (ref == null || payload == null || !ref.requestFingerprint().equals(payload.requestFingerprint())
                    || !ref.responseFingerprint().equals(payload.responseFingerprint())) throw invalid();
            this.ref = ref;
            this.payload = payload;
        }

        public WorldDraftRedactedPayloadRef ref() { return ref; }
        WorldDraftRedactedPayload payload() { return payload; }
        Object request() { return payload.request(); }
        Object response() { return payload.response(); }

        @Override public String toString() { return "StoredPayload[ref=" + ref + "]"; }

        private static WorldDraftCandidateException invalid() {
            return new WorldDraftCandidateException(WorldDraftCandidateException.Code.SOURCE_INTEGRITY);
        }
    }
}
