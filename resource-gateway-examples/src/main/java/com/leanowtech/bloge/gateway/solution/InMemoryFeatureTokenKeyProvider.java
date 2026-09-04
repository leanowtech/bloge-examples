package com.leanowtech.bloge.gateway.solution;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Small immutable key ring for local demos, tests, and externally supplied rotating keys. */
public final class InMemoryFeatureTokenKeyProvider implements FeatureTokenKeyProvider {
    private final String activeKeyId;
    private final Map<String, byte[]> keys;

    /** Creates a key ring whose map may include verify-only historical generations. */
    public InMemoryFeatureTokenKeyProvider(String activeKeyId, Map<String, byte[]> keys) {
        this.activeKeyId = activeKeyId == null ? "" : activeKeyId.trim();
        LinkedHashMap<String, byte[]> copy = new LinkedHashMap<>();
        if (keys != null) keys.forEach((key, value) -> copy.put(key, value == null ? null : value.clone()));
        this.keys = Map.copyOf(copy);
        if (!this.keys.containsKey(this.activeKeyId)) {
            throw new IllegalArgumentException("Active Feature token key is unavailable");
        }
        this.keys.forEach((key, value) -> new SigningKey(key, value));
    }

    /** Creates one process-local generation suitable only for a local demonstration. */
    public static InMemoryFeatureTokenKeyProvider ephemeral() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        return new InMemoryFeatureTokenKeyProvider("local-ephemeral", Map.of("local-ephemeral", secret));
    }

    /**
     * Parses {@code keyId=base64(secret)} entries. An entirely empty configuration creates one
     * process-local demo key; a partial or malformed production configuration fails closed.
     */
    public static InMemoryFeatureTokenKeyProvider fromConfiguration(String activeKeyId, String keyRing) {
        String active = activeKeyId == null ? "" : activeKeyId.trim();
        String encodedRing = keyRing == null ? "" : keyRing.trim();
        if (active.isBlank() && encodedRing.isBlank()) return ephemeral();
        if (active.isBlank() || encodedRing.isBlank()) {
            throw new IllegalArgumentException("Feature token key configuration is incomplete");
        }
        LinkedHashMap<String, byte[]> decoded = new LinkedHashMap<>();
        for (String entry : encodedRing.split(",")) {
            String[] parts = entry.trim().split("=", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()
                    || decoded.containsKey(parts[0].trim())) {
                throw new IllegalArgumentException("Feature token key ring is invalid");
            }
            try {
                decoded.put(parts[0].trim(), java.util.Base64.getDecoder().decode(parts[1].trim()));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Feature token key ring is invalid");
            }
        }
        return new InMemoryFeatureTokenKeyProvider(active, decoded);
    }

    @Override
    public SigningKey active() {
        return new SigningKey(activeKeyId, keys.get(activeKeyId));
    }

    @Override
    public Optional<byte[]> verifySecret(String keyId) {
        byte[] secret = keys.get(keyId == null ? "" : keyId.trim());
        return secret == null ? Optional.empty() : Optional.of(secret.clone());
    }
}
