package com.leanowtech.bloge.gateway.solution;

import java.util.Optional;

/** Rotation-aware source of HMAC keys used only for short-lived Feature value tokens. */
public interface FeatureTokenKeyProvider {
    /** @return the current signing generation */
    SigningKey active();

    /** Returns an active or verify-only key while its rotation grace window remains open. */
    Optional<byte[]> verifySecret(String keyId);

    /** Immutable key generation; the secret is defensively copied at every boundary. */
    record SigningKey(String keyId, byte[] secret) {
        /** Validates a non-empty key id and a minimum 256-bit secret. */
        public SigningKey {
            keyId = keyId == null ? "" : keyId.trim();
            secret = secret == null ? new byte[0] : secret.clone();
            if (keyId.isBlank() || secret.length < 32) {
                throw new IllegalArgumentException("Feature token signing key is invalid");
            }
        }

        @Override
        public byte[] secret() {
            return secret.clone();
        }
    }
}
