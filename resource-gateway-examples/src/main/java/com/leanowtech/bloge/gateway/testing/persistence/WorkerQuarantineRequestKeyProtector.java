package com.leanowtech.bloge.gateway.testing.persistence;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Creates non-reversible, rotation-aware indexes for worker-quarantine idempotency requests.
 *
 * <p>Request identifiers may be low entropy, so an unkeyed digest is not sufficient protection
 * against an offline dictionary attack on retained tombstones. This authority derives a dedicated
 * HMAC key from each configured root, separates both derivation and message domains from every
 * other worker-quarantine credential, and binds the request kind and exact scope to the index.
 * New indexes use only the active key; reads may derive a bounded active-first candidate set from
 * retained verification keys while an online rotation is in progress.</p>
 *
 * <p>The key ring is deliberately capped at sixteen generations. Operators must keep an old key
 * until no unexpired tombstone references it; database readiness enforces that invariant.</p>
 */
public final class WorkerQuarantineRequestKeyProtector {

    private static final String VERSION = "v1";
    private static final int ROOT_KEY_BYTES = 32;
    private static final int HMAC_SHA_256_BYTES = 32;
    private static final int MAX_KEYS = 16;
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final byte[] KEY_CONTEXT =
            "bloge.workerQuarantine.requestIndexHmacKey.v1"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] MESSAGE_CONTEXT =
            "bloge.workerQuarantine.requestIndex.v1"
                    .getBytes(StandardCharsets.UTF_8);

    private final String activeKeyId;
    private final Map<String, SecretKeySpec> keys;

    /**
     * Parses a comma-separated {@code keyId=base64Key} request-index key ring.
     *
     * @param activeKeyId key generation used for every new request index
     * @param configuredKeyRing active and verification-only 32-byte root keys
     * @return validated request-index authority
     */
    public static WorkerQuarantineRequestKeyProtector fromConfiguration(
            String activeKeyId, String configuredKeyRing) {
        return new WorkerQuarantineRequestKeyProtector(
                activeKeyId, parseKeyRing(configuredKeyRing));
    }

    WorkerQuarantineRequestKeyProtector(String activeKeyId, Map<String, byte[]> keys) {
        this.activeKeyId = requiredKeyId(activeKeyId);
        Objects.requireNonNull(keys, "keys");
        if (keys.isEmpty() || keys.size() > MAX_KEYS) {
            throw new IllegalArgumentException(
                    "Worker quarantine request-index key ring must contain 1 through 16 keys");
        }
        Map<String, SecretKeySpec> validated = new LinkedHashMap<>();
        keys.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String keyId = requiredKeyId(entry.getKey());
                    byte[] rootKey = Objects.requireNonNull(
                            entry.getValue(), "key material").clone();
                    if (rootKey.length != ROOT_KEY_BYTES) {
                        throw new IllegalArgumentException(
                                "Worker quarantine request-index keys must be 32 bytes");
                    }
                    SecretKeySpec derived = new SecretKeySpec(derive(rootKey), "HmacSHA256");
                    if (validated.putIfAbsent(keyId, derived) != null) {
                        throw new IllegalArgumentException(
                                "Duplicate worker quarantine request-index key id: " + keyId);
                    }
                });
        if (!validated.containsKey(this.activeKeyId)) {
            throw new IllegalArgumentException(
                    "Active worker quarantine request-index key is absent from the key ring");
        }
        this.keys = Map.copyOf(validated);
    }

    /**
     * Derives the active deterministic index for one caller request identity.
     *
     * @param requestKind stable command category
     * @param scopeKey canonical worker authorization scope key
     * @param requestId caller-stable idempotency identifier
     * @return active key identifier and versioned HMAC index
     */
    public IndexKey protect(String requestKind, String scopeKey, String requestId) {
        return protectWith(activeKeyId, requestKind, scopeKey, requestId);
    }

    /**
     * Returns every bounded lookup candidate needed during a two-phase key rotation.
     *
     * <p>The active generation is first; verification-only generations follow in stable key-ID
     * order. The result contains no request identifier or root key material.</p>
     *
     * @param requestKind stable command category
     * @param scopeKey canonical worker authorization scope key
     * @param requestId caller-stable idempotency identifier
     * @return immutable active-first candidate list
     */
    public List<IndexKey> lookupCandidates(
            String requestKind, String scopeKey, String requestId) {
        List<String> keyIds = new ArrayList<>(keys.keySet());
        keyIds.sort(Comparator.naturalOrder());
        keyIds.remove(activeKeyId);
        keyIds.addFirst(activeKeyId);
        return keyIds.stream()
                .map(keyId -> protectWith(keyId, requestKind, scopeKey, requestId))
                .toList();
    }

    /**
     * Constant-time verifies one persisted keyed index against its supplied request identity.
     *
     * @param requestKind stable command category
     * @param scopeKey canonical worker authorization scope key
     * @param requestId caller-stable idempotency identifier
     * @param keyId persisted non-secret key generation
     * @param storedKey persisted versioned HMAC index
     * @return {@code true} only for the exact request identity and key generation
     */
    public boolean matches(
            String requestKind,
            String scopeKey,
            String requestId,
            String keyId,
            String storedKey) {
        byte[] supplied = parseIndex(storedKey);
        byte[] expected = indexMac(requireKey(keyId),
                required(requestKind, "requestKind"),
                required(scopeKey, "scopeKey"),
                required(requestId, "requestId"));
        return MessageDigest.isEqual(expected, supplied);
    }

    /**
     * Reports whether a persisted key generation is available for lookup.
     *
     * @param keyId persisted non-secret key generation
     * @return whether this process can verify the generation
     */
    public boolean containsKey(String keyId) {
        return keys.containsKey(requiredKeyId(keyId));
    }

    /**
     * Reports whether a matched tombstone should be lazily re-keyed.
     *
     * @param keyId persisted non-secret key generation
     * @return {@code true} for a verification-only generation
     */
    public boolean requiresRekey(String keyId) {
        return !activeKeyId.equals(requiredKeyId(keyId));
    }

    /** @return configured write-key identifier without exposing key material */
    public String activeKeyId() {
        return activeKeyId;
    }

    private IndexKey protectWith(
            String keyId, String requestKind, String scopeKey, String requestId) {
        byte[] mac = indexMac(requireKey(keyId),
                required(requestKind, "requestKind"),
                required(scopeKey, "scopeKey"),
                required(requestId, "requestId"));
        return new IndexKey(keyId, VERSION + "." + ENCODER.encodeToString(mac));
    }

    private SecretKeySpec requireKey(String keyId) {
        SecretKeySpec key = keys.get(requiredKeyId(keyId));
        if (key == null) {
            throw new IllegalStateException(
                    "Worker quarantine request-index key is unavailable");
        }
        return key;
    }

    private static byte[] derive(byte[] rootKey) {
        try {
            Mac kdf = Mac.getInstance("HmacSHA256");
            kdf.init(new SecretKeySpec(rootKey, "HmacSHA256"));
            return kdf.doFinal(KEY_CONTEXT);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(
                    "Worker quarantine request-index key derivation failed", failure);
        }
    }

    private static byte[] indexMac(
            SecretKeySpec key, String requestKind, String scopeKey, String requestId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            updateLengthPrefixed(mac, MESSAGE_CONTEXT);
            updateLengthPrefixed(mac, requestKind.getBytes(StandardCharsets.UTF_8));
            updateLengthPrefixed(mac, scopeKey.getBytes(StandardCharsets.UTF_8));
            updateLengthPrefixed(mac, requestId.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(
                    "Worker quarantine request-index protection failed", failure);
        }
    }

    private static void updateLengthPrefixed(Mac mac, byte[] value) {
        mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        mac.update(value);
    }

    private static byte[] parseIndex(String storedKey) {
        String safe = required(storedKey, "storedKey");
        String prefix = VERSION + ".";
        if (!safe.startsWith(prefix)) {
            throw new IllegalStateException("Worker quarantine request index is invalid");
        }
        try {
            byte[] decoded = DECODER.decode(safe.substring(prefix.length()));
            if (decoded.length != HMAC_SHA_256_BYTES) {
                throw new IllegalStateException("Worker quarantine request index is invalid");
            }
            return decoded;
        } catch (IllegalArgumentException malformedBase64) {
            throw new IllegalStateException("Worker quarantine request index is invalid");
        }
    }

    private static Map<String, byte[]> parseKeyRing(String configuredKeyRing) {
        String safeKeyRing = required(configuredKeyRing, "configuredKeyRing");
        Map<String, byte[]> parsed = new LinkedHashMap<>();
        for (String entry : safeKeyRing.split(",", -1)) {
            int separator = entry.indexOf('=');
            if (separator < 1 || separator == entry.length() - 1) {
                throw new IllegalArgumentException(
                        "Worker quarantine request-index key ring must use keyId=base64Key entries");
            }
            String keyId = requiredKeyId(entry.substring(0, separator).trim());
            byte[] key;
            try {
                key = Base64.getDecoder().decode(entry.substring(separator + 1).trim());
            } catch (IllegalArgumentException malformedBase64) {
                throw new IllegalArgumentException(
                        "Worker quarantine request-index key is not valid base64: " + keyId);
            }
            if (parsed.putIfAbsent(keyId, key) != null) {
                throw new IllegalArgumentException(
                        "Duplicate worker quarantine request-index key id: " + keyId);
            }
        }
        return parsed;
    }

    private static String requiredKeyId(String value) {
        String safe = required(value, "keyId");
        if (!KEY_ID.matcher(safe).matches()) {
            throw new IllegalArgumentException(
                    "Worker quarantine request-index key ids must match " + KEY_ID.pattern());
        }
        return safe;
    }

    private static String required(String value, String name) {
        String safe = Objects.requireNonNull(value, name).trim();
        if (safe.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return safe;
    }

    /**
     * Non-secret persisted representation of one request identity index.
     *
     * @param keyId key generation used to derive the HMAC key
     * @param value versioned HMAC-SHA-256 index
     */
    public record IndexKey(String keyId, String value) {
        /** Validates a complete bounded persisted representation. */
        public IndexKey {
            keyId = requiredKeyId(keyId);
            value = required(value, "value");
            if (value.length() > 80) {
                throw new IllegalArgumentException("Request index is too long");
            }
            try {
                parseIndex(value);
            } catch (IllegalStateException invalid) {
                throw new IllegalArgumentException("Request index is invalid", invalid);
            }
        }
    }
}
