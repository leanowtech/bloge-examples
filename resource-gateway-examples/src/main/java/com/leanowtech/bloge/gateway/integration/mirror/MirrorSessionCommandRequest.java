package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;

import java.util.Map;
import java.util.Objects;

/**
 * Strict state-transition command for one existing mirror session.
 *
 * @param schemaVersion command wire version
 * @param writeEffectRef exact admitted write effect
 * @param expectedStateFingerprint optional optimistic fence for a new commit; an exact
 *                                 idempotency-journal replay is returned before this fence
 * @param input detached business command input
 */
public record MirrorSessionCommandRequest(
        String schemaVersion,
        MirrorArtifactRef writeEffectRef,
        String expectedStateFingerprint,
        Map<String, Object> input
) {
    /** Current session command version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorSessionCommandRequest.v1";

    /** Freezes input and validates exact effect and optional state fences. */
    public MirrorSessionCommandRequest {
        schemaVersion = version(schemaVersion);
        writeEffectRef = Objects.requireNonNull(writeEffectRef, "writeEffectRef");
        if (!"WRITE_EFFECT".equals(writeEffectRef.kind())) {
            throw new IllegalArgumentException(
                    "writeEffectRef must reference WRITE_EFFECT");
        }
        expectedStateFingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                expectedStateFingerprint, "expectedStateFingerprint");
        input = ProtocolJsonValue.freezeMap(input);
    }

    private static String version(String value) {
        String normalized = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported mirror session command schemaVersion");
        }
        return normalized;
    }
}
