package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.Map;

/** Content addresses for the redacted request/response pair; it contains no values. */
public record WorldDraftRedactedPayloadRef(String requestFingerprint,
                                           String responseFingerprint,
                                           String pairFingerprint,
                                           String tenantId,
                                           String candidateId,
                                           long artifactRevision) {
    public WorldDraftRedactedPayloadRef {
        tenantId = text(tenantId, 255);
        candidateId = text(candidateId, 255);
        if (artifactRevision < 1 || !fp(requestFingerprint) || !fp(responseFingerprint) || !fp(pairFingerprint)) throw invalid();
        String expected = fingerprint(requestFingerprint, responseFingerprint);
        if (!expected.equals(pairFingerprint)) throw invalid();
    }

    public static WorldDraftRedactedPayloadRef of(String tenantId, String candidateId, long artifactRevision,
                                                  WorldDraftRedactedPayload payload) {
        if (payload == null) throw invalid();
        return new WorldDraftRedactedPayloadRef(payload.requestFingerprint(), payload.responseFingerprint(),
                fingerprint(payload.requestFingerprint(), payload.responseFingerprint()), tenantId, candidateId,
                artifactRevision);
    }

    /** Stable payload-free identity persisted by a draft rule and a published behavior. */
    public String fingerprint() {
        return VisualBundleFingerprint.fromMaterial(Map.of(
                "tenantId", tenantId,
                "candidateId", candidateId,
                "artifactRevision", artifactRevision,
                "requestFingerprint", requestFingerprint,
                "responseFingerprint", responseFingerprint,
                "pairFingerprint", pairFingerprint));
    }

    private static String fingerprint(String request, String response) {
        return VisualBundleFingerprint.fromMaterial(Map.of("requestFingerprint", request,
                "responseFingerprint", response));
    }
    private static boolean fp(String value) { return value != null && value.matches("sha256:[a-f0-9]{64}"); }
    private static String text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) throw invalid();
        return value.trim();
    }
    private static WorldDraftCandidateException invalid() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.SOURCE_INTEGRITY);
    }
}
