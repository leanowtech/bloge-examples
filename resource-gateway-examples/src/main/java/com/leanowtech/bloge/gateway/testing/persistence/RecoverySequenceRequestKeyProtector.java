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
 * Creates non-reversible, rotation-aware indexes for recovery-sequence request identities.
 *
 * <p>A sequence idempotency key may be human-readable and low entropy. Retaining an unkeyed
 * digest after the detailed command is erased would therefore permit offline dictionary attacks.
 * This authority derives a dedicated HMAC key from every configured root, uses independent key
 * and message domains, and binds tenant and environment to the request identifier. New indexes
 * use only the active generation; reads try a bounded active-first set during key rotation.</p>
 *
 * <p>Because the plaintext request identifier is cryptographically erased, an old tombstone
 * cannot be re-keyed. A verification-only key must remain configured until every tombstone that
 * references it has expired; repository startup enforces that invariant.</p>
 */
public final class RecoverySequenceRequestKeyProtector {

    private static final String VERSION = "v1";
    private static final int ROOT_KEY_BYTES = 32;
    private static final int HMAC_SHA_256_BYTES = 32;
    private static final int MAX_KEYS = 16;
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final byte[] KEY_CONTEXT =
            "bloge.durableRecoverySequence.requestIndexHmacKey.v1"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] MESSAGE_CONTEXT =
            "bloge.durableRecoverySequence.requestIndex.v1"
                    .getBytes(StandardCharsets.UTF_8);

    private final String activeKeyId;
    private final Map<String, SecretKeySpec> keys;

    /**
     * Parses a comma-separated {@code keyId=base64Key} request-index key ring.
     *
     * @param activeKeyId key generation used for every new tombstone
     * @param configuredKeyRing active and verification-only 32-byte root keys
     * @return validated recovery-sequence request-index authority
     */
    public static RecoverySequenceRequestKeyProtector fromConfiguration(
            String activeKeyId,
            String configuredKeyRing) {
        return new RecoverySequenceRequestKeyProtector(
                activeKeyId, parseKeyRing(configuredKeyRing));
    }

    RecoverySequenceRequestKeyProtector(String activeKeyId, Map<String, byte[]> keys) {
        this.activeKeyId = requiredKeyId(activeKeyId);
        Objects.requireNonNull(keys, "keys");
        if (keys.isEmpty() || keys.size() > MAX_KEYS) {
            throw new IllegalArgumentException(
                    "Recovery-sequence request-index key ring must contain 1 through 16 keys");
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
                                "Recovery-sequence request-index keys must be 32 bytes");
                    }
                    SecretKeySpec derived = new SecretKeySpec(derive(rootKey), "HmacSHA256");
                    if (validated.putIfAbsent(keyId, derived) != null) {
                        throw new IllegalArgumentException(
                                "Duplicate recovery-sequence request-index key id: " + keyId);
                    }
                });
        if (!validated.containsKey(this.activeKeyId)) {
            throw new IllegalArgumentException(
                    "Active recovery-sequence request-index key is absent from the key ring");
        }
        this.keys = Map.copyOf(validated);
    }

    /**
     * Derives the active deterministic index for one scoped request identity.
     *
     * @param tenantId exact authenticated tenant
     * @param environmentId exact isolated environment
     * @param requestId caller-stable recovery-sequence idempotency key
     * @return active key identifier and versioned HMAC index
     */
    public IndexKey protect(String tenantId, String environmentId, String requestId) {
        return protectWith(activeKeyId, tenantId, environmentId, requestId);
    }

    /**
     * Returns every bounded lookup candidate needed during a key rotation.
     *
     * @param tenantId exact authenticated tenant
     * @param environmentId exact isolated environment
     * @param requestId caller-stable recovery-sequence idempotency key
     * @return immutable active-first candidate list without plaintext key material
     */
    public List<IndexKey> lookupCandidates(
            String tenantId,
            String environmentId,
            String requestId) {
        List<String> keyIds = new ArrayList<>(keys.keySet());
        keyIds.sort(Comparator.naturalOrder());
        keyIds.remove(activeKeyId);
        keyIds.addFirst(activeKeyId);
        return keyIds.stream()
                .map(keyId -> protectWith(keyId, tenantId, environmentId, requestId))
                .toList();
    }

    /**
     * Constant-time verifies a persisted index against one supplied request identity.
     *
     * @param tenantId exact authenticated tenant
     * @param environmentId exact isolated environment
     * @param requestId caller-stable recovery-sequence idempotency key
     * @param keyId persisted non-secret key generation
     * @param storedKey persisted versioned HMAC index
     * @return whether the request identity exactly matches the persisted index
     */
    public boolean matches(
            String tenantId,
            String environmentId,
            String requestId,
            String keyId,
            String storedKey) {
        byte[] supplied = parseIndex(storedKey);
        byte[] expected = indexMac(requireKey(keyId),
                required(tenantId, "tenantId"),
                required(environmentId, "environmentId"),
                required(requestId, "requestId"));
        return MessageDigest.isEqual(expected, supplied);
    }

    /**
     * Reports whether a persisted key generation remains available for lookup.
     *
     * @param keyId persisted non-secret key generation
     * @return whether this process can verify the generation
     */
    public boolean containsKey(String keyId) {
        return keys.containsKey(requiredKeyId(keyId));
    }

    /** @return configured write-key identifier without exposing key material */
    public String activeKeyId() {
        return activeKeyId;
    }

    private IndexKey protectWith(
            String keyId,
            String tenantId,
            String environmentId,
            String requestId) {
        byte[] mac = indexMac(requireKey(keyId),
                required(tenantId, "tenantId"),
                required(environmentId, "environmentId"),
                required(requestId, "requestId"));
        return new IndexKey(keyId, VERSION + "." + ENCODER.encodeToString(mac));
    }

    private SecretKeySpec requireKey(String keyId) {
        SecretKeySpec key = keys.get(requiredKeyId(keyId));
        if (key == null) {
            throw new IllegalStateException(
                    "Recovery-sequence request-index key is unavailable");
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
                    "Recovery-sequence request-index key derivation failed", failure);
        }
    }

    private static byte[] indexMac(
            SecretKeySpec key,
            String tenantId,
            String environmentId,
            String requestId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            updateLengthPrefixed(mac, MESSAGE_CONTEXT);
            updateLengthPrefixed(mac, tenantId.getBytes(StandardCharsets.UTF_8));
            updateLengthPrefixed(mac, environmentId.getBytes(StandardCharsets.UTF_8));
            updateLengthPrefixed(mac, requestId.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(
                    "Recovery-sequence request-index protection failed", failure);
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
            throw new IllegalStateException("Recovery-sequence request index is invalid");
        }
        try {
            byte[] decoded = DECODER.decode(safe.substring(prefix.length()));
            if (decoded.length != HMAC_SHA_256_BYTES) {
                throw new IllegalStateException(
                        "Recovery-sequence request index is invalid");
            }
            return decoded;
        } catch (IllegalArgumentException malformedBase64) {
            throw new IllegalStateException(
                    "Recovery-sequence request index is invalid");
        }
    }

    private static Map<String, byte[]> parseKeyRing(String configuredKeyRing) {
        String safeKeyRing = required(configuredKeyRing, "configuredKeyRing");
        Map<String, byte[]> parsed = new LinkedHashMap<>();
        for (String entry : safeKeyRing.split(",", -1)) {
            int separator = entry.indexOf('=');
            if (separator < 1 || separator == entry.length() - 1) {
                throw new IllegalArgumentException(
                        "Recovery-sequence request-index key ring must use "
                                + "keyId=base64Key entries");
            }
            String keyId = requiredKeyId(entry.substring(0, separator).trim());
            byte[] key;
            try {
                key = Base64.getDecoder().decode(entry.substring(separator + 1).trim());
            } catch (IllegalArgumentException malformedBase64) {
                throw new IllegalArgumentException(
                        "Recovery-sequence request-index key is not valid base64: " + keyId);
            }
            if (parsed.putIfAbsent(keyId, key) != null) {
                throw new IllegalArgumentException(
                        "Duplicate recovery-sequence request-index key id: " + keyId);
            }
        }
        return parsed;
    }

    private static String requiredKeyId(String value) {
        String safe = required(value, "keyId");
        if (!KEY_ID.matcher(safe).matches()) {
            throw new IllegalArgumentException(
                    "Recovery-sequence request-index key ids must match " + KEY_ID.pattern());
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
