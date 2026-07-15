package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Locale;

/**
 * Exact capture command for importing one successful node attempt from the governed run vault.
 *
 * @param schemaVersion command protocol version
 * @param revision positive destination replay-payload revision
 * @param source exact source run and node-attempt coordinates
 * @param classification destination classification, which cannot downgrade the source
 * @param expiresAt requested hard expiry, capped by source retention and server policy
 */
public record ReplayPayloadCaptureRequest(
        String schemaVersion,
        long revision,
        Source source,
        String classification,
        Instant expiresAt
) {
    /** Current replay-payload capture command version. */
    public static final String SCHEMA_VERSION = "bloge.replayPayloadCaptureRequest.v1";

    /** Normalizes caller fields without weakening service-layer validation. */
    public ReplayPayloadCaptureRequest {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        classification = defaulted(classification, "INTERNAL").toUpperCase(Locale.ROOT);
    }

    /**
     * Optimistic exact source reference.
     *
     * @param runId governed visual run id
     * @param nodeId exact node id
     * @param attempt exact one-based attempt
     * @param runEvidenceFingerprint expected signed run material fingerprint
     * @param payloadFingerprint expected detached payload fingerprint
     */
    public record Source(
            String runId,
            String nodeId,
            int attempt,
            String runEvidenceFingerprint,
            String payloadFingerprint
    ) {
        /** Normalizes exact coordinates. */
        public Source {
            runId = normalized(runId);
            nodeId = normalized(nodeId);
            runEvidenceFingerprint = normalized(runEvidenceFingerprint);
            payloadFingerprint = normalized(payloadFingerprint);
        }
    }

    private static String defaulted(String value, String fallback) {
        String normalized = normalized(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
