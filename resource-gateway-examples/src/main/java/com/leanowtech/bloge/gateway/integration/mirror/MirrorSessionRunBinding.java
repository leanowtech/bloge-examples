package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.regex.Pattern;

/**
 * Public, payload-free coordinates that bind one mirror run to a reviewed session state head.
 *
 * <p>The authenticated enterprise scope remains server-owned. The caller supplies only a stable
 * session id and the state fingerprint it observed, preventing both cross-scope selection and an
 * unnoticed run against a concurrently changed virtual world.</p>
 *
 * @param sessionId active stateful mirror session identity
 * @param expectedStateFingerprint exact state head reviewed by the caller
 */
public record MirrorSessionRunBinding(
        String sessionId,
        String expectedStateFingerprint
) {
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates one exact payload-free run binding. */
    public MirrorSessionRunBinding {
        sessionId = required(sessionId, "sessionId");
        if (!IDENTIFIER.matcher(sessionId).matches()) {
            throw new IllegalArgumentException(
                    "sessionId contains unsupported characters");
        }
        expectedStateFingerprint = required(
                expectedStateFingerprint, "expectedStateFingerprint");
        if (!FINGERPRINT.matcher(expectedStateFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "expectedStateFingerprint must be canonical SHA-256");
        }
    }

    /** Keeps future binding extensions from accidentally exposing state payloads in logs. */
    @Override
    public String toString() {
        return "MirrorSessionRunBinding[sessionId=" + sessionId
                + ", expectedStateFingerprint="
                + expectedStateFingerprint + "]";
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank");
        }
        return normalized;
    }
}
