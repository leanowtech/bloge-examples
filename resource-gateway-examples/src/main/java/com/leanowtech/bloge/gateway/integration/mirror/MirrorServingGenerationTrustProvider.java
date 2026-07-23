package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Operator-owned trust policy for serving-generation authority keys.
 *
 * <p>Authority responses are data, not trust anchors. Implementations must resolve keys from
 * pinned configuration or a separately authenticated key-distribution channel. The lookup result
 * distinguishes an unknown key from provider outage so admission can fail closed with stable
 * operational semantics.</p>
 */
public interface MirrorServingGenerationTrustProvider {
    /** @return whether the trust source can currently resolve a coherent policy snapshot */
    boolean available();

    /**
     * Resolves one exact authority and key identity.
     *
     * @param authorityId pinned authority identity
     * @param keyId exact signing key identity
     * @return available, unknown, or unavailable result
     */
    Resolution resolve(String authorityId, String keyId);

    /** Creates a one-key immutable trust policy, primarily for embedded adapters and tests. */
    static MirrorServingGenerationTrustProvider fixed(AuthorityKey key) {
        AuthorityKey exact = Objects.requireNonNull(key, "key");
        return new MirrorServingGenerationTrustProvider() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public Resolution resolve(String authorityId, String keyId) {
                return exact.authorityId().equals(normalized(authorityId))
                        && exact.keyId().equals(normalized(keyId))
                        ? Resolution.available(exact)
                        : Resolution.notFound();
            }
        };
    }

    /** Returns an explicit fail-closed trust provider. */
    static MirrorServingGenerationTrustProvider unavailable() {
        return new MirrorServingGenerationTrustProvider() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public Resolution resolve(String authorityId, String keyId) {
                return Resolution.unavailable();
            }
        };
    }

    /** Closed key lookup outcomes. */
    enum Outcome {
        AVAILABLE,
        NOT_FOUND,
        UNAVAILABLE
    }

    /** Authority-key lifecycle admitted by local policy. */
    enum KeyState {
        ACTIVE,
        RETIRED,
        REVOKED
    }

    /**
     * One pinned public authority key.
     *
     * @param authorityId exact authority identity
     * @param keyId exact key identity
     * @param algorithm fixed signature algorithm
     * @param encodedPublicKey canonical X.509 SubjectPublicKeyInfo base64
     * @param notBefore inclusive verification bound
     * @param notAfter exclusive verification bound
     * @param state key lifecycle
     */
    record AuthorityKey(
            String authorityId,
            String keyId,
            String algorithm,
            String encodedPublicKey,
            Instant notBefore,
            Instant notAfter,
            KeyState state
    ) {
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,511}");

        /** Validates bounded Ed25519 public key policy material. */
        public AuthorityKey {
            authorityId = identifier(authorityId, "authorityId");
            keyId = identifier(keyId, "keyId");
            algorithm = required(algorithm, "algorithm", 32);
            encodedPublicKey = canonicalBase64(encodedPublicKey);
            notBefore = Objects.requireNonNull(notBefore, "notBefore");
            notAfter = Objects.requireNonNull(notAfter, "notAfter");
            state = Objects.requireNonNull(state, "state");
            if (!"Ed25519".equals(algorithm) || !notAfter.isAfter(notBefore)) {
                throw new IllegalArgumentException(
                        "serving-generation authority key is invalid");
            }
        }

        private static String identifier(String value, String field) {
            String exact = required(value, field, 512);
            if (!IDENTIFIER.matcher(exact).matches()) {
                throw new IllegalArgumentException(
                        field + " contains unsupported characters");
            }
            return exact;
        }

        private static String canonicalBase64(String value) {
            String exact = required(value, "encodedPublicKey", 16_384);
            try {
                byte[] decoded = Base64.getDecoder().decode(exact);
                if (decoded.length == 0 || !exact.equals(
                        Base64.getEncoder().encodeToString(decoded))) {
                    throw new IllegalArgumentException(
                            "encodedPublicKey must use canonical base64");
                }
                return exact;
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "encodedPublicKey must use canonical base64", invalid);
            }
        }
    }

    /**
     * Exact trust lookup result.
     *
     * @param outcome closed lookup outcome
     * @param key key only for AVAILABLE
     */
    record Resolution(Outcome outcome, AuthorityKey key) {
        /** Enforces one key only for an available result. */
        public Resolution {
            outcome = Objects.requireNonNull(outcome, "outcome");
            if ((outcome == Outcome.AVAILABLE) != (key != null)) {
                throw new IllegalArgumentException(
                        "only an available trust result may carry a key");
            }
        }

        /** @return available key result */
        public static Resolution available(AuthorityKey key) {
            return new Resolution(
                    Outcome.AVAILABLE, Objects.requireNonNull(key, "key"));
        }

        /** @return unknown key result */
        public static Resolution notFound() {
            return new Resolution(Outcome.NOT_FOUND, null);
        }

        /** @return provider outage result */
        public static Resolution unavailable() {
            return new Resolution(Outcome.UNAVAILABLE, null);
        }
    }

    private static String required(
            String value, String field, int maximumLength) {
        String exact = normalized(value);
        if (exact.isBlank() || exact.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must be non-blank and bounded");
        }
        return exact;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
