package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.Map;

/** Payload-free exact request rule emitted with a materialized World draft. */
public record WorldDraftRule(String requestSchemaFingerprint, String inputFingerprint,
                             String responseFingerprint, WorldDraftFragmentRef fragment,
                             WorldDraftRedactedPayloadRef redactedPayloadRef) {
    public WorldDraftRule(String requestSchemaFingerprint, String inputFingerprint,
                          String responseFingerprint) {
        this(requestSchemaFingerprint, inputFingerprint, responseFingerprint, null, null);
    }

    public WorldDraftRule(String requestSchemaFingerprint, String inputFingerprint,
                          String responseFingerprint, WorldDraftFragmentRef fragment) {
        this(requestSchemaFingerprint, inputFingerprint, responseFingerprint, fragment, null);
    }

    public WorldDraftRule {
        if (!fp(requestSchemaFingerprint) || !fp(inputFingerprint) || !fp(responseFingerprint)) {
            throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.MATERIALIZATION_INVALID);
        }
        if (redactedPayloadRef != null
                && (!inputFingerprint.equals(redactedPayloadRef.requestFingerprint())
                || !responseFingerprint.equals(redactedPayloadRef.responseFingerprint()))) {
            throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.MATERIALIZATION_INVALID);
        }
    }

    public String fingerprint() {
        return VisualBundleFingerprint.fromMaterial(Map.of("requestSchemaFingerprint", requestSchemaFingerprint,
                "inputFingerprint", inputFingerprint, "responseFingerprint", responseFingerprint,
                "fragmentFingerprint", fragment == null ? "" : fragment.fingerprint(),
                "redactedPayloadRefFingerprint", redactedPayloadRef == null ? "" : redactedPayloadRef.fingerprint()));
    }

    private static boolean fp(String value) { return value != null && value.matches("sha256:[a-f0-9]{64}"); }
}
