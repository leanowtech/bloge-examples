package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

/** Server-owned frozen redacted values; public methods expose fingerprints only. */
public final class WorldDraftRedactedPayload {
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private final Object request;
    private final Object response;
    private final String requestFingerprint;
    private final String responseFingerprint;

    WorldDraftRedactedPayload(Object request, Object response) {
        try {
            this.request = ProtocolJsonValue.freeze(request);
            this.response = ProtocolJsonValue.freeze(response);
            this.requestFingerprint = ProtocolFingerprint.of(MAPPER, this.request);
            this.responseFingerprint = ProtocolFingerprint.of(MAPPER, this.response);
        } catch (RuntimeException invalid) {
            throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.REDACTION_REQUIRED);
        }
    }

    public String requestFingerprint() { return requestFingerprint; }
    public String responseFingerprint() { return responseFingerprint; }

    Object request() { return request; }
    Object response() { return response; }

    @Override public String toString() { return "WorldDraftRedactedPayload[fingerprinted]"; }
}
