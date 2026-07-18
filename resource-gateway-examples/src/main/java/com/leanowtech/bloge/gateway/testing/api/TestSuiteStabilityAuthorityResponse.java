package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Short-lived, signed current-authority decision returned by an external policy decision point.
 *
 * <p>Every request coordinate is echoed and included in {@code materialFingerprint}. The detached
 * Ed25519 signature covers that fingerprint, so a valid decision cannot be moved to another job,
 * principal, request or replay challenge.</p>
 *
 * @param schemaVersion exact response protocol generation
 * @param requestId echoed authorization-call identity
 * @param challenge echoed replay challenge
 * @param jobId echoed durable job identity
 * @param authorizationRequestFingerprint echoed request material fingerprint
 * @param principalFingerprint echoed stable principal fingerprint
 * @param decision definitive signed current-authority decision
 * @param failureCode stable policy code only for {@link Decision#REVOKED}
 * @param authorityId external authority identity
 * @param policyRevision exact policy revision used for the decision
 * @param decisionId unique authority decision identity
 * @param issuedAt decision issue time
 * @param expiresAt exclusive short-lived decision expiry
 * @param materialFingerprint canonical semantic response fingerprint
 * @param signature detached Ed25519 signature over {@code materialFingerprint}
 */
public record TestSuiteStabilityAuthorityResponse(
        String schemaVersion,
        String requestId,
        String challenge,
        String jobId,
        String authorizationRequestFingerprint,
        String principalFingerprint,
        Decision decision,
        String failureCode,
        String authorityId,
        String policyRevision,
        String decisionId,
        Instant issuedAt,
        Instant expiresAt,
        String materialFingerprint,
        SignatureBlock signature) {

    /** Current version of the private PDP-to-worker protocol. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteStabilityAuthorityResponse.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43,128}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Definitive outcomes that are meaningful only after signature verification. */
    public enum Decision {
        /** The exact durable job may execute under current policy. */
        AUTHORIZED,
        /** Current policy definitively denies the exact durable job. */
        REVOKED
    }

    /** Validates shape and state-dependent response fields before trust evaluation. */
    public TestSuiteStabilityAuthorityResponse {
        schemaVersion = normalized(schemaVersion);
        requestId = normalized(requestId);
        challenge = normalized(challenge);
        jobId = normalized(jobId);
        authorizationRequestFingerprint = normalized(authorizationRequestFingerprint)
                .toLowerCase(Locale.ROOT);
        principalFingerprint = normalized(principalFingerprint).toLowerCase(Locale.ROOT);
        failureCode = normalized(failureCode).toUpperCase(Locale.ROOT);
        authorityId = normalized(authorityId);
        policyRevision = normalized(policyRevision);
        decisionId = normalized(decisionId);
        materialFingerprint = normalized(materialFingerprint).toLowerCase(Locale.ROOT);
        decision = Objects.requireNonNull(decision, "decision");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        signature = Objects.requireNonNull(signature, "signature");
        boolean validFailure = decision == Decision.AUTHORIZED
                ? failureCode.isBlank() : CODE.matcher(failureCode).matches();
        if (!SCHEMA_VERSION.equals(schemaVersion) || !IDENTIFIER.matcher(requestId).matches()
                || !CHALLENGE.matcher(challenge).matches()
                || !IDENTIFIER.matcher(jobId).matches()
                || !FINGERPRINT.matcher(authorizationRequestFingerprint).matches()
                || !FINGERPRINT.matcher(principalFingerprint).matches() || !validFailure
                || !IDENTIFIER.matcher(authorityId).matches()
                || !IDENTIFIER.matcher(policyRevision).matches()
                || !IDENTIFIER.matcher(decisionId).matches()
                || !expiresAt.isAfter(issuedAt)
                || !FINGERPRINT.matcher(materialFingerprint).matches()) {
            throw new IllegalArgumentException("Invalid suite-stability authority response");
        }
    }

    /**
     * Recomputes the semantic fingerprint before any signature can be trusted.
     *
     * @param objectMapper canonical JSON mapper
     * @return true only when every semantic field is covered by the fingerprint
     */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return materialFingerprint.equals(ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(objectMapper, "objectMapper"), material(), 64 * 1024));
    }

    /** @return canonical response material covered by the detached signature */
    public Material material() {
        return new Material(schemaVersion, requestId, challenge, jobId,
                authorizationRequestFingerprint, principalFingerprint, decision, failureCode,
                authorityId, policyRevision, decisionId, issuedAt, expiresAt);
    }

    /** Canonical semantic material independently reproducible by the external authority. */
    public record Material(
            String schemaVersion,
            String requestId,
            String challenge,
            String jobId,
            String authorizationRequestFingerprint,
            String principalFingerprint,
            Decision decision,
            String failureCode,
            String authorityId,
            String policyRevision,
            String decisionId,
            Instant issuedAt,
            Instant expiresAt) {
    }

    /**
     * Rotation-aware detached Ed25519 signature.
     *
     * @param keyId configured public-key identity
     * @param algorithm exact signature algorithm
     * @param signature base64-encoded 64-byte Ed25519 signature
     */
    public record SignatureBlock(String keyId, String algorithm, String signature) {

        /** Rejects ambiguous algorithms and malformed signature bytes. */
        public SignatureBlock {
            keyId = normalized(keyId);
            algorithm = normalized(algorithm);
            signature = normalized(signature);
            try {
                if (!IDENTIFIER.matcher(keyId).matches() || !"Ed25519".equals(algorithm)
                        || Base64.getDecoder().decode(signature).length != 64) {
                    throw new IllegalArgumentException("Invalid authority response signature");
                }
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Invalid authority response signature", invalid);
            }
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
