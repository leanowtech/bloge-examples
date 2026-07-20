package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Credential-free, challenge-bound request sent to a test-secret authority.
 *
 * <p>The request carries only the already authenticated and payload-free
 * {@link TestSecretResolutionContext}. It deliberately excludes transport credentials,
 * correlation ids, graph inputs, fixture payloads, evidence and previously resolved values. The
 * context and whole request are independently content-addressed before transport.</p>
 *
 * @param schemaVersion exact private protocol generation
 * @param requestId unique authority-call identity
 * @param challenge fresh 256-bit or stronger base64url challenge
 * @param requestedAt Resource Gateway wall-clock observation
 * @param action fixed least-privilege authority action
 * @param context exact enterprise, purpose, target, fixture and reference closure
 * @param contextFingerprint canonical fingerprint of {@code context}
 * @param requestFingerprint canonical fingerprint of every preceding field
 */
public record TestSecretAuthorityRequest(
        String schemaVersion,
        String requestId,
        String challenge,
        Instant requestedAt,
        String action,
        TestSecretResolutionContext context,
        String contextFingerprint,
        String requestFingerprint) {

    /** Current Resource Gateway to test-secret authority protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testSecretAuthorityRequest.v1";
    /** Least-privilege action understood by the external authority. */
    public static final String ACTION = "RESOLVE_TEST_SECRET_CLOSURE";

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43,128}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates the complete, already-fingerprinted request envelope. */
    public TestSecretAuthorityRequest {
        schemaVersion = normalized(schemaVersion);
        requestId = normalized(requestId);
        challenge = normalized(challenge);
        action = normalized(action).toUpperCase(Locale.ROOT);
        contextFingerprint = normalized(contextFingerprint).toLowerCase(Locale.ROOT);
        requestFingerprint = normalized(requestFingerprint).toLowerCase(Locale.ROOT);
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        context = Objects.requireNonNull(context, "context");
        if (!SCHEMA_VERSION.equals(schemaVersion) || !IDENTIFIER.matcher(requestId).matches()
                || !CHALLENGE.matcher(challenge).matches() || !ACTION.equals(action)
                || !FINGERPRINT.matcher(contextFingerprint).matches()
                || !FINGERPRINT.matcher(requestFingerprint).matches()) {
            throw new IllegalArgumentException("Invalid test-secret authority request");
        }
    }

    /**
     * Creates an exact content-addressed request from a trusted local resolution context.
     *
     * @param objectMapper canonical protocol mapper
     * @param context exact locally authorized dependency closure
     * @param requestId fresh authority-call identity
     * @param challenge fresh 256-bit or stronger base64url challenge
     * @param requestedAt current trusted local time
     * @return immutable private authority request
     */
    public static TestSecretAuthorityRequest create(
            ObjectMapper objectMapper,
            TestSecretResolutionContext context,
            String requestId,
            String challenge,
            Instant requestedAt) {
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper");
        TestSecretResolutionContext exactContext = Objects.requireNonNull(context, "context");
        String exactContextFingerprint = exactContext.fingerprint(mapper);
        Material material = new Material(SCHEMA_VERSION, normalized(requestId),
                normalized(challenge), Objects.requireNonNull(requestedAt, "requestedAt"), ACTION,
                exactContext, exactContextFingerprint);
        return new TestSecretAuthorityRequest(material.schemaVersion(), material.requestId(),
                material.challenge(), material.requestedAt(), material.action(),
                material.context(), material.contextFingerprint(),
                ProtocolFingerprint.ofBounded(mapper, material, 256 * 1024));
    }

    /**
     * Recomputes both nested fingerprints before an external response can be trusted.
     *
     * @param objectMapper canonical protocol mapper
     * @return true only when the context and request closure are unchanged
     */
    public boolean fingerprintsVerified(ObjectMapper objectMapper) {
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper");
        return contextFingerprint.equals(context.fingerprint(mapper))
                && requestFingerprint.equals(ProtocolFingerprint.ofBounded(
                mapper, material(), 256 * 1024));
    }

    /** @return canonical request material covered by {@link #requestFingerprint()} */
    public Material material() {
        return new Material(schemaVersion, requestId, challenge, requestedAt, action, context,
                contextFingerprint);
    }

    /** Canonical request material independently reproducible by an authority implementation. */
    public record Material(
            String schemaVersion,
            String requestId,
            String challenge,
            Instant requestedAt,
            String action,
            TestSecretResolutionContext context,
            String contextFingerprint) {
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
