package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Immutable startup-configured trust store; enterprises may replace it with a dynamic KMS/JWKS adapter. */
public final class ConfiguredIntegrationJwtTrustStore implements IntegrationJwtTrustStore {
    private final Map<String, VerificationKey> keys;
    private final Set<String> revokedTokenIds;

    public ConfiguredIntegrationJwtTrustStore(List<VerificationKey> keys, Set<String> revokedTokenIds) {
        Map<String, VerificationKey> indexed = new LinkedHashMap<>();
        for (VerificationKey key : keys == null ? List.<VerificationKey>of() : keys) {
            if (key != null && indexed.putIfAbsent(key.keyId(), key) != null) {
                throw new IllegalArgumentException("Duplicate integration JWT key id: " + key.keyId());
            }
        }
        if (indexed.isEmpty()) {
            throw new IllegalArgumentException("At least one trusted integration JWT key is required");
        }
        this.keys = Map.copyOf(indexed);
        Set<String> revoked = new LinkedHashSet<>();
        if (revokedTokenIds != null) {
            revokedTokenIds.stream().map(ConfiguredIntegrationJwtTrustStore::normalize)
                    .filter(value -> !value.isBlank()).forEach(revoked::add);
        }
        this.revokedTokenIds = Set.copyOf(revoked);
    }

    @Override
    public Optional<VerificationKey> find(String keyId) {
        return Optional.ofNullable(keys.get(normalize(keyId)));
    }

    @Override
    public boolean isTokenRevoked(String tokenId) {
        return revokedTokenIds.contains(normalize(tokenId));
    }

    @Override
    public Snapshot snapshot() {
        Instant now = Instant.now();
        int active = (int) keys.values().stream().filter(key -> key.activeAt(now)).count();
        int revoked = (int) keys.values().stream().filter(VerificationKey::revoked).count();
        return new Snapshot(keys.size(), active, revoked, revokedTokenIds.size());
    }

    public static VerificationKey fromBase64Der(String keyId,
                                                String algorithm,
                                                String publicKeyBase64,
                                                Instant notBefore,
                                                Instant expiresAt,
                                                boolean enabled,
                                                boolean revoked) {
        try {
            byte[] encoded = Base64.getDecoder().decode(normalize(publicKeyBase64));
            String normalizedAlgorithm = normalize(algorithm).toUpperCase(Locale.ROOT);
            String keyAlgorithm = "RS256".equals(normalizedAlgorithm) ? "RSA" : "Ed25519";
            PublicKey publicKey = KeyFactory.getInstance(keyAlgorithm)
                    .generatePublic(new X509EncodedKeySpec(encoded));
            return new VerificationKey(keyId, algorithm, publicKey, notBefore, expiresAt, enabled, revoked);
        } catch (RuntimeException | java.security.GeneralSecurityException failure) {
            throw new IllegalArgumentException("Invalid integration JWT public key for key id " + keyId, failure);
        }
    }

    public static ConfiguredIntegrationJwtTrustStore fromJson(ObjectMapper objectMapper,
                                                               String trustedKeysJson,
                                                               Set<String> revokedKeyIds,
                                                               Set<String> revokedTokenIds) {
        try {
            JsonNode root = objectMapper.readTree(normalize(trustedKeysJson));
            if (root == null || !root.isArray() || root.isEmpty() || root.size() > 32) {
                throw new IllegalArgumentException("Integration JWT trusted keys must be a non-empty JSON array");
            }
            Set<String> revokedKeys = revokedKeyIds == null ? Set.of() : Set.copyOf(revokedKeyIds);
            java.util.ArrayList<VerificationKey> parsed = new java.util.ArrayList<>();
            for (JsonNode item : root) {
                String keyId = requiredText(item, "keyId");
                String algorithm = requiredText(item, "algorithm");
                parsed.add(fromBase64Der(keyId, algorithm, requiredText(item, "publicKeyBase64"),
                        instant(item, "notBefore", Instant.MIN), instant(item, "expiresAt", Instant.MAX),
                        !item.has("enabled") || item.path("enabled").asBoolean(false),
                        revokedKeys.contains(keyId) || item.path("revoked").asBoolean(false)));
            }
            return new ConfiguredIntegrationJwtTrustStore(parsed, revokedTokenIds);
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("Invalid integration JWT trusted key JSON", failure);
        }
    }

    private static String requiredText(JsonNode item, String name) {
        String value = item.path(name).isTextual() ? item.path(name).textValue().trim() : "";
        if (value.isBlank() || value.length() > 16_384) {
            throw new IllegalArgumentException("Missing or oversized integration JWT key field: " + name);
        }
        return value;
    }

    private static Instant instant(JsonNode item, String name, Instant fallback) {
        if (!item.has(name)) {
            return fallback;
        }
        if (!item.path(name).isTextual()) {
            throw new IllegalArgumentException("Integration JWT key time must be ISO-8601: " + name);
        }
        return Instant.parse(item.path(name).textValue());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
