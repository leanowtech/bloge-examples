package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.Map;

/** Payload-free provenance attached to an unpublished materialization result. */
public record WorldDraftProvenance(
        String candidateId,
        long candidateRevision,
        String sourceFingerprint,
        String schemaFingerprint,
        String redactionPolicyFingerprint,
        String redactedPayloadFingerprint,
        String materializationFingerprint
) {
    public WorldDraftProvenance {
        if (candidateId == null || candidateId.isBlank() || candidateRevision < 1
                || !fp(sourceFingerprint) || !fp(schemaFingerprint) || !fp(redactionPolicyFingerprint)
                || !fp(redactedPayloadFingerprint) || !fp(materializationFingerprint)) throw invalid();
        candidateId = candidateId.trim();
    }

    public static WorldDraftProvenance of(WorldDraftCandidate candidate, WorldDraftRule rule) {
        if (candidate == null || rule == null) throw invalid();
        return new WorldDraftProvenance(candidate.candidateId(), candidate.revision(),
                candidate.source().fingerprint(), candidate.schemaFingerprint(),
                candidate.redactionPolicyFingerprint(), candidate.redactedPayloadFingerprint(), rule.fingerprint());
    }

    boolean matches(WorldDraftCandidate candidate, WorldDraftRule rule) {
        return candidate != null && rule != null && candidateId.equals(candidate.candidateId())
                && candidateRevision <= candidate.revision()
                && sourceFingerprint.equals(candidate.source().fingerprint())
                && schemaFingerprint.equals(candidate.schemaFingerprint())
                && redactionPolicyFingerprint.equals(candidate.redactionPolicyFingerprint())
                && redactedPayloadFingerprint.equals(candidate.redactedPayloadFingerprint())
                && materializationFingerprint.equals(rule.fingerprint());
    }

    private static boolean fp(String value) { return value != null && value.matches("sha256:[a-f0-9]{64}"); }

    private static WorldDraftCandidateException invalid() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.MATERIALIZATION_INVALID);
    }
}
