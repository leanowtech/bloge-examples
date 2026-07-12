package com.leanowtech.bloge.gateway.integration;

import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Optional;
import java.util.Locale;

/** Supplies trusted JWT verification keys and revocation state to the identity resolver. */
public interface IntegrationJwtTrustStore {
    Optional<VerificationKey> find(String keyId);

    boolean isTokenRevoked(String tokenId);

    Snapshot snapshot();

    record VerificationKey(String keyId,
                           String algorithm,
                           PublicKey publicKey,
                           Instant notBefore,
                           Instant expiresAt,
                           boolean enabled,
                           boolean revoked) {
        public VerificationKey {
            keyId = normalize(keyId);
            String normalizedAlgorithm = normalize(algorithm).toUpperCase(Locale.ROOT);
            algorithm = normalizedAlgorithm.equals("EDDSA") ? "EdDSA" : normalizedAlgorithm;
            notBefore = notBefore == null ? Instant.MIN : notBefore;
            expiresAt = expiresAt == null ? Instant.MAX : expiresAt;
            if (keyId.isBlank() || publicKey == null) {
                throw new IllegalArgumentException("JWT verification key id and public key are required");
            }
            if (!algorithm.equals("RS256") && !algorithm.equals("EdDSA")) {
                throw new IllegalArgumentException("Only RS256 and EdDSA integration JWT keys are supported");
            }
            if (algorithm.equals("RS256")
                    && (!(publicKey instanceof RSAPublicKey rsaKey) || rsaKey.getModulus().bitLength() < 2048)) {
                throw new IllegalArgumentException("Integration RS256 keys must use RSA with at least 2048 bits");
            }
            String keyAlgorithm = publicKey.getAlgorithm();
            if (algorithm.equals("EdDSA")
                    && !(keyAlgorithm.equalsIgnoreCase("EdDSA") || keyAlgorithm.equalsIgnoreCase("Ed25519"))) {
                throw new IllegalArgumentException("Integration EdDSA keys must use Ed25519");
            }
        }

        public boolean activeAt(Instant observedAt) {
            Instant now = observedAt == null ? Instant.now() : observedAt;
            return enabled && !revoked && !now.isBefore(notBefore) && expiresAt.isAfter(now);
        }
    }

    record Snapshot(int trustedKeyCount,
                    int activeKeyCount,
                    int revokedKeyCount,
                    int revokedTokenCount) {
        public Snapshot {
            trustedKeyCount = Math.max(0, trustedKeyCount);
            activeKeyCount = Math.max(0, activeKeyCount);
            revokedKeyCount = Math.max(0, revokedKeyCount);
            revokedTokenCount = Math.max(0, revokedTokenCount);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
