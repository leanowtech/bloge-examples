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
 * Creates non-reversible, rotation-aware indexes for retired stability-job request identities.
 *
 * <p>Caller idempotency keys may be human-readable and low entropy. A plain digest retained after
 * job erasure would permit offline enumeration. This authority derives a stability-job-specific
 * HMAC key from every configured root and binds tenant and environment to the request identifier.
 * Verification-only keys must remain configured until all tombstones written by them expire.</p>
 */
public final class TestSuiteStabilityJobRequestKeyProtector {

    private static final String VERSION = "v1";
    private static final int ROOT_KEY_BYTES = 32;
    private static final int HMAC_BYTES = 32;
    private static final int MAX_KEYS = 16;
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final byte[] KEY_CONTEXT =
            "bloge.testSuiteStabilityJob.requestIndexHmacKey.v1"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] MESSAGE_CONTEXT =
            "bloge.testSuiteStabilityJob.requestIndex.v1"
                    .getBytes(StandardCharsets.UTF_8);

    private final String activeKeyId;
    private final Map<String, SecretKeySpec> keys;

    /**
     * Parses a comma-separated {@code keyId=base64Key} request-index key ring.
     *
     * @param activeKeyId generation used for every new tombstone
     * @param configuredKeyRing active and verification-only 32-byte roots
     * @return validated stability-job request-index authority
     */
    public static TestSuiteStabilityJobRequestKeyProtector fromConfiguration(
            String activeKeyId, String configuredKeyRing) {
        return new TestSuiteStabilityJobRequestKeyProtector(
                activeKeyId, parseKeyRing(configuredKeyRing));
    }

    TestSuiteStabilityJobRequestKeyProtector(
            String activeKeyId, Map<String, byte[]> roots) {
        this.activeKeyId = requiredKeyId(activeKeyId);
        Objects.requireNonNull(roots, "roots");
        if (roots.isEmpty() || roots.size() > MAX_KEYS) {
            throw new IllegalArgumentException(
                    "Stability-job request-index key ring must contain 1 through 16 keys");
        }
        LinkedHashMap<String, SecretKeySpec> validated = new LinkedHashMap<>();
        roots.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String keyId = requiredKeyId(entry.getKey());
            byte[] root = Objects.requireNonNull(entry.getValue(), "key material").clone();
            if (root.length != ROOT_KEY_BYTES) {
                throw new IllegalArgumentException(
                        "Stability-job request-index keys must be 32 bytes");
            }
            if (validated.putIfAbsent(
                    keyId, new SecretKeySpec(derive(root), "HmacSHA256")) != null) {
                throw new IllegalArgumentException(
                        "Duplicate stability-job request-index key id: " + keyId);
            }
        });
        if (!validated.containsKey(this.activeKeyId)) {
            throw new IllegalArgumentException(
                    "Active stability-job request-index key is absent from the key ring");
        }
        keys = Map.copyOf(validated);
    }

    /** Derives the active deterministic index for one exact scoped request. */
    public IndexKey protect(String tenantId, String environmentId, String requestId) {
        return protectWith(activeKeyId, tenantId, environmentId, requestId);
    }

    /** Returns bounded active-first lookup candidates needed during key rotation. */
    public List<IndexKey> lookupCandidates(
            String tenantId, String environmentId, String requestId) {
        List<String> keyIds = new ArrayList<>(keys.keySet());
        keyIds.sort(Comparator.naturalOrder());
        keyIds.remove(activeKeyId);
        keyIds.addFirst(activeKeyId);
        return keyIds.stream()
                .map(keyId -> protectWith(keyId, tenantId, environmentId, requestId))
                .toList();
    }

    /** Constant-time verifies a stored index against one supplied scoped request identity. */
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

    /** @return whether a retained tombstone generation can still be verified */
    public boolean containsKey(String keyId) {
        return keys.containsKey(requiredKeyId(keyId));
    }

    /** @return active non-secret key generation identifier */
    public String activeKeyId() {
        return activeKeyId;
    }

    private IndexKey protectWith(
            String keyId, String tenantId, String environmentId, String requestId) {
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
                    "Stability-job request-index key is unavailable");
        }
        return key;
    }

    private static byte[] derive(byte[] root) {
        try {
            Mac kdf = Mac.getInstance("HmacSHA256");
            kdf.init(new SecretKeySpec(root, "HmacSHA256"));
            return kdf.doFinal(KEY_CONTEXT);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(
                    "Stability-job request-index key derivation failed", failure);
        }
    }

    private static byte[] indexMac(
            SecretKeySpec key, String tenantId, String environmentId, String requestId) {
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
                    "Stability-job request-index protection failed", failure);
        }
    }

    private static void updateLengthPrefixed(Mac mac, byte[] value) {
        mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        mac.update(value);
    }

    private static byte[] parseIndex(String value) {
        String safe = required(value, "storedKey");
        String prefix = VERSION + ".";
        if (!safe.startsWith(prefix)) {
            throw new IllegalStateException("Stability-job request index is invalid");
        }
        try {
            byte[] decoded = DECODER.decode(safe.substring(prefix.length()));
            if (decoded.length != HMAC_BYTES) {
                throw new IllegalStateException("Stability-job request index is invalid");
            }
            return decoded;
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalStateException("Stability-job request index is invalid");
        }
    }

    private static Map<String, byte[]> parseKeyRing(String configured) {
        String keyRing = required(configured, "configuredKeyRing");
        LinkedHashMap<String, byte[]> parsed = new LinkedHashMap<>();
        for (String entry : keyRing.split(",", -1)) {
            int separator = entry.indexOf('=');
            if (separator < 1 || separator == entry.length() - 1) {
                throw new IllegalArgumentException(
                        "Stability-job request-index key ring must use keyId=base64Key entries");
            }
            String keyId = requiredKeyId(entry.substring(0, separator).trim());
            byte[] key;
            try {
                key = Base64.getDecoder().decode(entry.substring(separator + 1).trim());
            } catch (IllegalArgumentException invalidBase64) {
                throw new IllegalArgumentException(
                        "Stability-job request-index key is not valid base64: " + keyId);
            }
            if (parsed.putIfAbsent(keyId, key) != null) {
                throw new IllegalArgumentException(
                        "Duplicate stability-job request-index key id: " + keyId);
            }
        }
        return parsed;
    }

    private static String requiredKeyId(String value) {
        String safe = required(value, "keyId");
        if (!KEY_ID.matcher(safe).matches()) {
            throw new IllegalArgumentException(
                    "Stability-job request-index key ids must match " + KEY_ID.pattern());
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

    /** Non-secret persisted representation of one scoped request identity index. */
    public record IndexKey(String keyId, String value) {
        /** Validates the key generation and versioned HMAC representation. */
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
