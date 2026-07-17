package com.leanowtech.bloge.gateway.testing.persistence;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Protects replayable worker-quarantine claim tokens and live token-verification fences.
 *
 * <p>The first configured key is not inferred: callers name one active key and retain any prior
 * decrypt-only keys in the same key ring until every stored envelope has been rewrapped. Envelope
 * authentication also binds caller-supplied associated data, so moving ciphertext between command
 * rows fails closed. A domain-separated HMAC key derived from each root key protects active-control
 * equality checks without persisting a second bearer token.</p>
 */
public final class WorkerQuarantineClaimTokenProtector {

    private static final String VERSION = "v1";
    private static final int AES_256_BYTES = 32;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int HMAC_SHA_256_BYTES = 32;
    private static final String ACTIVE_FENCE_MAC_VERSION = "v1";
    private static final byte[] ACTIVE_FENCE_KEY_CONTEXT =
            "bloge.workerQuarantine.activeFenceHmacKey.v1"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] ACTIVE_FENCE_MESSAGE_CONTEXT =
            "bloge.workerQuarantine.activeFenceMac.v1"
                    .getBytes(StandardCharsets.UTF_8);
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final String activeKeyId;
    private final Map<String, KeyMaterial> keys;
    private final SecureRandom secureRandom;

    /**
     * Parses a comma-separated {@code keyId=base64Key} key ring.
     *
     * @param activeKeyId key used for every new envelope
     * @param configuredKeyRing active and decrypt-only AES-256 keys
     * @return validated token protector
     */
    public static WorkerQuarantineClaimTokenProtector fromConfiguration(
            String activeKeyId, String configuredKeyRing) {
        return new WorkerQuarantineClaimTokenProtector(
                activeKeyId, parseKeyRing(configuredKeyRing), new SecureRandom());
    }

    WorkerQuarantineClaimTokenProtector(
            String activeKeyId, Map<String, byte[]> keys, SecureRandom secureRandom) {
        this.activeKeyId = requiredKeyId(activeKeyId);
        Objects.requireNonNull(keys, "keys");
        Map<String, KeyMaterial> validated = new LinkedHashMap<>();
        keys.forEach((keyId, value) -> {
            String safeKeyId = requiredKeyId(keyId);
            byte[] key = Objects.requireNonNull(value, "key material").clone();
            if (key.length != AES_256_BYTES) {
                throw new IllegalArgumentException(
                        "Worker quarantine token keys must be 32-byte AES-256 keys");
            }
            KeyMaterial material = new KeyMaterial(new SecretKeySpec(key, "AES"),
                    new SecretKeySpec(deriveActiveFenceKey(key), "HmacSHA256"));
            if (validated.putIfAbsent(safeKeyId, material) != null) {
                throw new IllegalArgumentException(
                        "Duplicate worker quarantine token key id: " + safeKeyId);
            }
        });
        if (!validated.containsKey(this.activeKeyId)) {
            throw new IllegalArgumentException(
                    "Active worker quarantine token key is absent from the key ring");
        }
        this.keys = Map.copyOf(validated);
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    /**
     * Encrypts and authenticates one token with fresh randomness and the active key.
     *
     * @param token opaque server-minted claim token
     * @param associatedData stable command-row identity used only as authenticated context
     * @return versioned envelope containing no plaintext token
     */
    public String protect(String token, String associatedData) {
        String safeToken = required(token, "token");
        String safeAssociatedData = required(associatedData, "associatedData");
        byte[] nonce = new byte[GCM_NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE,
                keys.get(activeKeyId).encryptionKey(), nonce,
                safeAssociatedData, safeToken.getBytes(StandardCharsets.UTF_8));
        return VERSION + "." + activeKeyId + "." + ENCODER.encodeToString(nonce) + "."
                + ENCODER.encodeToString(ciphertext);
    }

    /**
     * Authenticates and decrypts one envelope using its named key.
     *
     * @param envelope versioned protected token
     * @param associatedData exact context supplied when the token was protected
     * @return original opaque claim token
     */
    public String unprotect(String envelope, String associatedData) {
        ParsedEnvelope parsed = parse(envelope);
        KeyMaterial key = keys.get(parsed.keyId());
        if (key == null) {
            throw new IllegalStateException(
                    "Worker quarantine claim token key is unavailable");
        }
        byte[] plaintext = crypt(Cipher.DECRYPT_MODE, key.encryptionKey(), parsed.nonce(),
                required(associatedData, "associatedData"), parsed.ciphertext());
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    /**
     * Reports whether an envelope was written by a key that is no longer active.
     *
     * @param envelope protected token envelope
     * @return {@code true} when a successful read should rewrap the token
     */
    public boolean requiresRewrap(String envelope) {
        return !activeKeyId.equals(parse(envelope).keyId());
    }

    /**
     * Returns the non-secret key identifier carried by an envelope.
     *
     * @param envelope protected token envelope
     * @return key identifier
     */
    public String keyId(String envelope) {
        return parse(envelope).keyId();
    }

    /**
     * Returns the configured write key without exposing any key material.
     *
     * @return active key identifier
     */
    public String activeKeyId() {
        return activeKeyId;
    }

    /**
     * Creates a deterministic, domain-separated MAC for one live active-control fence.
     *
     * <p>The MAC key is derived from the active root key with a fixed HMAC context and is never
     * reused as the AES-GCM key. Associated data must bind the complete non-secret control
     * identity. The returned value contains no claim token and is not a replay credential.</p>
     *
     * @param token opaque server-minted claim token
     * @param associatedData canonical active-control identity
     * @return active key identifier and versioned HMAC value
     */
    public ActiveFence protectActiveFence(String token, String associatedData) {
        byte[] mac = activeFenceMac(keys.get(activeKeyId).activeFenceKey(),
                required(token, "token"), required(associatedData, "associatedData"));
        return new ActiveFence(activeKeyId,
                ACTIVE_FENCE_MAC_VERSION + "." + ENCODER.encodeToString(mac));
    }

    /**
     * Constant-time verifies a caller-presented token against one stored active-control MAC.
     *
     * @param token caller-presented opaque claim token
     * @param associatedData exact control identity used when the MAC was created
     * @param keyId non-secret stored key identifier
     * @param storedMac versioned stored MAC
     * @return {@code true} only for the exact token and control identity
     */
    public boolean matchesActiveFence(
            String token, String associatedData, String keyId, String storedMac) {
        KeyMaterial material = keys.get(requiredKeyId(keyId));
        if (material == null) {
            throw new IllegalStateException(
                    "Worker quarantine active fence key is unavailable");
        }
        byte[] supplied = parseActiveFenceMac(storedMac);
        byte[] expected = activeFenceMac(material.activeFenceKey(),
                required(token, "token"), required(associatedData, "associatedData"));
        return MessageDigest.isEqual(expected, supplied);
    }

    /**
     * Reports whether a stored active-control MAC names a decrypt-only key.
     *
     * @param keyId stored MAC key identifier
     * @return {@code true} when startup should verify and re-key the control
     */
    public boolean activeFenceRequiresRekey(String keyId) {
        return !activeKeyId.equals(requiredKeyId(keyId));
    }

    private byte[] crypt(
            int mode,
            SecretKeySpec key,
            byte[] nonce,
            String associatedData,
            byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (AEADBadTagException authenticationFailure) {
            throw new IllegalStateException(
                    "Worker quarantine claim token envelope authentication failed");
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(
                    "Worker quarantine claim token protection failed", failure);
        }
    }

    private static byte[] deriveActiveFenceKey(byte[] rootKey) {
        try {
            Mac kdf = Mac.getInstance("HmacSHA256");
            kdf.init(new SecretKeySpec(rootKey, "HmacSHA256"));
            return kdf.doFinal(ACTIVE_FENCE_KEY_CONTEXT);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(
                    "Worker quarantine active fence key derivation failed", failure);
        }
    }

    private static byte[] activeFenceMac(
            SecretKeySpec key, String token, String associatedData) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            updateLengthPrefixed(mac, ACTIVE_FENCE_MESSAGE_CONTEXT);
            updateLengthPrefixed(mac, associatedData.getBytes(StandardCharsets.UTF_8));
            updateLengthPrefixed(mac, token.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(
                    "Worker quarantine active fence protection failed", failure);
        }
    }

    private static void updateLengthPrefixed(Mac mac, byte[] value) {
        mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        mac.update(value);
    }

    private static byte[] parseActiveFenceMac(String storedMac) {
        String safe = required(storedMac, "storedMac");
        String prefix = ACTIVE_FENCE_MAC_VERSION + ".";
        if (!safe.startsWith(prefix)) {
            throw new IllegalStateException("Worker quarantine active fence MAC is invalid");
        }
        try {
            byte[] decoded = DECODER.decode(safe.substring(prefix.length()));
            if (decoded.length != HMAC_SHA_256_BYTES) {
                throw new IllegalStateException(
                        "Worker quarantine active fence MAC is invalid");
            }
            return decoded;
        } catch (IllegalArgumentException malformedBase64) {
            throw new IllegalStateException("Worker quarantine active fence MAC is invalid");
        }
    }

    private static ParsedEnvelope parse(String envelope) {
        String safeEnvelope = required(envelope, "envelope");
        String[] parts = safeEnvelope.split("\\.", 4);
        if (parts.length != 4 || !VERSION.equals(parts[0]) || !KEY_ID.matcher(parts[1]).matches()) {
            throw new IllegalStateException("Worker quarantine claim token envelope is invalid");
        }
        try {
            byte[] nonce = DECODER.decode(parts[2]);
            byte[] ciphertext = DECODER.decode(parts[3]);
            if (nonce.length != GCM_NONCE_BYTES || ciphertext.length <= GCM_TAG_BITS / Byte.SIZE) {
                throw new IllegalStateException(
                        "Worker quarantine claim token envelope is invalid");
            }
            return new ParsedEnvelope(parts[1], nonce, ciphertext);
        } catch (IllegalArgumentException malformedBase64) {
            throw new IllegalStateException(
                    "Worker quarantine claim token envelope is invalid");
        }
    }

    private static Map<String, byte[]> parseKeyRing(String configuredKeyRing) {
        String safeKeyRing = required(configuredKeyRing, "configuredKeyRing");
        Map<String, byte[]> parsed = new LinkedHashMap<>();
        for (String entry : safeKeyRing.split(",")) {
            int separator = entry.indexOf('=');
            if (separator < 1 || separator == entry.length() - 1) {
                throw new IllegalArgumentException(
                        "Worker quarantine token key ring must use keyId=base64Key entries");
            }
            String keyId = requiredKeyId(entry.substring(0, separator).trim());
            byte[] key;
            try {
                key = Base64.getDecoder().decode(entry.substring(separator + 1).trim());
            } catch (IllegalArgumentException malformedBase64) {
                throw new IllegalArgumentException(
                        "Worker quarantine token key is not valid base64: " + keyId);
            }
            if (parsed.putIfAbsent(keyId, key) != null) {
                throw new IllegalArgumentException(
                        "Duplicate worker quarantine token key id: " + keyId);
            }
        }
        return parsed;
    }

    private static String requiredKeyId(String value) {
        String safe = required(value, "keyId");
        if (!KEY_ID.matcher(safe).matches()) {
            throw new IllegalArgumentException(
                    "Worker quarantine token key ids must match " + KEY_ID.pattern());
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

    private record ParsedEnvelope(String keyId, byte[] nonce, byte[] ciphertext) {
    }

    private record KeyMaterial(
            SecretKeySpec encryptionKey, SecretKeySpec activeFenceKey) {
    }

    /**
     * Non-secret persisted representation of one active maintenance fence.
     *
     * @param keyId key generation used to derive the MAC key
     * @param mac versioned HMAC-SHA-256 value
     */
    public record ActiveFence(String keyId, String mac) {
        /** Validates a complete bounded representation. */
        public ActiveFence {
            keyId = requiredKeyId(keyId);
            mac = required(mac, "mac");
            if (mac.length() > 80) {
                throw new IllegalArgumentException("Active fence MAC is too long");
            }
            try {
                parseActiveFenceMac(mac);
            } catch (IllegalStateException invalid) {
                throw new IllegalArgumentException("Active fence MAC is invalid", invalid);
            }
        }
    }
}
