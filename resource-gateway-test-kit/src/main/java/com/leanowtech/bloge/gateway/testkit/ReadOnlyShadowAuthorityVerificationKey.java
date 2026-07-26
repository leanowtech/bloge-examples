package com.leanowtech.bloge.gateway.testkit;

import java.time.Instant;
import java.util.Base64;

/**
 * Independently provisioned public key for read-only Shadow online-authority verification.
 *
 * <p>This key is local trust input. It must not be discovered from the untrusted grant,
 * kill-switch, or guard-policy publication being verified.</p>
 *
 * @param keyId stable authority key identity
 * @param algorithm fixed signature algorithm
 * @param encodedPublicKey canonical base64 X.509 SubjectPublicKeyInfo bytes
 * @param issuer exact governance or operational authority identity
 * @param scope exact enterprise namespace delegated to this key
 * @param publicationType only authority protocol this key may verify
 * @param notBefore inclusive signing-time bound
 * @param notAfter exclusive signing-time bound
 * @param retiredAt exclusive retirement boundary; required only for RETIRED
 * @param state current local key lifecycle
 */
public record ReadOnlyShadowAuthorityVerificationKey(
        String keyId,
        String algorithm,
        String encodedPublicKey,
        String issuer,
        ReadOnlyShadowAuthorityBinding.Scope scope,
        ReadOnlyShadowAuthorityBinding.Type publicationType,
        Instant notBefore,
        Instant notAfter,
        Instant retiredAt,
        State state
) {
    /** Validates one bounded external public trust input. */
    public ReadOnlyShadowAuthorityVerificationKey {
        keyId = identifier(keyId, "keyId");
        algorithm = normalized(algorithm);
        encodedPublicKey = canonicalBase64(encodedPublicKey);
        issuer = identifier(issuer, "issuer");
        if (scope == null || publicationType == null) {
            throw new IllegalArgumentException(
                    "read-only Shadow authority key delegation is required");
        }
        notBefore = requiredTime(notBefore, "notBefore");
        notAfter = requiredTime(notAfter, "notAfter");
        state = state == null ? State.REVOKED : state;
        if (state == State.RETIRED) {
            retiredAt = requiredTime(retiredAt, "retiredAt");
        } else if (retiredAt != null) {
            throw new IllegalArgumentException(
                    "retiredAt is valid only for a retired authority key");
        }
        if (!"Ed25519".equals(algorithm) || !notAfter.isAfter(notBefore)) {
            throw new IllegalArgumentException(
                    "read-only Shadow authority verification key is invalid");
        }
        if (retiredAt != null
                && (retiredAt.isBefore(notBefore)
                || retiredAt.isAfter(notAfter))) {
            throw new IllegalArgumentException(
                    "read-only Shadow authority retirement is invalid");
        }
    }

    /** Public-key lifecycle states enforced by the offline consumer. */
    public enum State {
        /** Key may sign and verify current publications. */
        ACTIVE,
        /** Key may verify signatures created strictly before its recorded retirement. */
        RETIRED,
        /** Key must not verify any publication. */
        REVOKED
    }

    /**
     * Reports whether local policy permits signature verification.
     *
     * @param signedAt exact detached-signature time
     * @return true for active keys or signatures strictly preceding retirement
     */
    public boolean verificationAllowed(Instant signedAt) {
        Instant exact = requiredTime(signedAt, "signedAt");
        return state == State.ACTIVE
                || state == State.RETIRED
                && exact.isBefore(retiredAt);
    }

    private static String identifier(String value, String field) {
        String exact = normalized(value);
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String canonicalBase64(String value) {
        String exact = normalized(value);
        try {
            byte[] decoded = Base64.getDecoder().decode(exact);
            if (decoded.length == 0
                    || !exact.equals(Base64.getEncoder().encodeToString(decoded))) {
                throw new IllegalArgumentException("public key is not canonical");
            }
            return exact;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "read-only Shadow authority public key must be canonical base64",
                    invalid);
        }
    }

    private static Instant requiredTime(Instant value, String field) {
        if (value == null || Instant.EPOCH.equals(value)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
