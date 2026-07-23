package com.leanowtech.bloge.gateway.testing.persistence;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * AES-256-GCM envelope protection for stateful mirror session aggregates.
 *
 * <p>Every envelope names its key generation and uses fresh 96-bit randomness. Callers bind the
 * complete non-secret scope, session, revision, and payload fingerprint as associated data, so a
 * ciphertext cannot be moved to another row or revision. The first configured key is never
 * inferred; deployments explicitly select the active write key and retain prior decrypt-only keys
 * until stored envelopes have been rewrapped.</p>
 */
public final class MirrorStatePayloadProtector {
    private static final String VERSION = "v1";
    private static final int AES_256_BYTES = 32;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Base64.Encoder ENCODER =
            Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final String activeKeyId;
    private final Map<String, SecretKeySpec> keys;
    private final SecureRandom secureRandom;

    /**
     * Parses a comma-separated {@code keyId=base64Key} key ring.
     *
     * @param activeKeyId key generation used for every new envelope
     * @param configuredKeyRing active and decrypt-only AES-256 keys
     * @return validated protector
     */
    public static MirrorStatePayloadProtector fromConfiguration(
            String activeKeyId, String configuredKeyRing) {
        return new MirrorStatePayloadProtector(
                activeKeyId, parseKeyRing(configuredKeyRing), new SecureRandom());
    }

    MirrorStatePayloadProtector(
            String activeKeyId,
            Map<String, byte[]> keys,
            SecureRandom secureRandom) {
        this.activeKeyId = requiredKeyId(activeKeyId);
        Objects.requireNonNull(keys, "keys");
        Map<String, SecretKeySpec> validated = new LinkedHashMap<>();
        keys.forEach((keyId, raw) -> {
            String safeKeyId = requiredKeyId(keyId);
            byte[] key = Objects.requireNonNull(raw, "key material").clone();
            if (key.length != AES_256_BYTES) {
                throw new IllegalArgumentException(
                        "Mirror state payload keys must be 32-byte AES-256 keys");
            }
            if (validated.putIfAbsent(
                    safeKeyId, new SecretKeySpec(key, "AES")) != null) {
                throw new IllegalArgumentException(
                        "Duplicate mirror state payload key id: " + safeKeyId);
            }
        });
        if (!validated.containsKey(this.activeKeyId)) {
            throw new IllegalArgumentException(
                    "Active mirror state payload key is absent from the key ring");
        }
        this.keys = Map.copyOf(validated);
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    /**
     * Encrypts and authenticates one canonical session aggregate.
     *
     * @param plaintext detached canonical payload bytes
     * @param associatedData exact non-secret row and revision identity
     * @return versioned authenticated envelope
     */
    public String protect(byte[] plaintext, String associatedData) {
        byte[] input = Objects.requireNonNull(plaintext, "plaintext").clone();
        if (input.length == 0) {
            throw new IllegalArgumentException(
                    "mirror state plaintext must not be empty");
        }
        String aad = required(associatedData, "associatedData");
        byte[] nonce = new byte[GCM_NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE,
                keys.get(activeKeyId), nonce, aad, input);
        return VERSION + "." + activeKeyId + "."
                + ENCODER.encodeToString(nonce) + "."
                + ENCODER.encodeToString(ciphertext);
    }

    /**
     * Authenticates and decrypts one envelope.
     *
     * @param envelope versioned protected payload
     * @param associatedData exact context supplied during encryption
     * @return detached plaintext bytes
     */
    public byte[] unprotect(String envelope, String associatedData) {
        ParsedEnvelope parsed = parse(envelope);
        SecretKeySpec key = keys.get(parsed.keyId());
        if (key == null) {
            throw new IllegalStateException(
                    "Mirror state payload key is unavailable");
        }
        return crypt(Cipher.DECRYPT_MODE, key, parsed.nonce(),
                required(associatedData, "associatedData"), parsed.ciphertext());
    }

    /**
     * Reports whether a successful read should be rewrapped with the active key.
     *
     * @param envelope protected payload envelope
     * @return {@code true} when the envelope names a decrypt-only key
     */
    public boolean requiresRewrap(String envelope) {
        return !activeKeyId.equals(parse(envelope).keyId());
    }

    /** @return active non-secret key generation identifier */
    public String activeKeyId() {
        return activeKeyId;
    }

    private static byte[] crypt(
            int mode,
            SecretKeySpec key,
            byte[] nonce,
            String associatedData,
            byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(associatedData.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (AEADBadTagException authenticationFailure) {
            throw new IllegalStateException(
                    "Mirror state payload envelope authentication failed");
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(
                    "Mirror state payload protection failed", failure);
        }
    }

    private static ParsedEnvelope parse(String envelope) {
        String safe = required(envelope, "envelope");
        String[] parts = safe.split("\\.", 4);
        if (parts.length != 4
                || !VERSION.equals(parts[0])
                || !KEY_ID.matcher(parts[1]).matches()) {
            throw new IllegalStateException(
                    "Mirror state payload envelope is invalid");
        }
        try {
            byte[] nonce = DECODER.decode(parts[2]);
            byte[] ciphertext = DECODER.decode(parts[3]);
            if (nonce.length != GCM_NONCE_BYTES
                    || ciphertext.length <= GCM_TAG_BITS / Byte.SIZE) {
                throw new IllegalStateException(
                        "Mirror state payload envelope is invalid");
            }
            return new ParsedEnvelope(parts[1], nonce, ciphertext);
        } catch (IllegalArgumentException malformedBase64) {
            throw new IllegalStateException(
                    "Mirror state payload envelope is invalid");
        }
    }

    private static Map<String, byte[]> parseKeyRing(String value) {
        String configured = required(value, "configuredKeyRing");
        Map<String, byte[]> parsed = new LinkedHashMap<>();
        for (String entry : configured.split(",")) {
            int separator = entry.indexOf('=');
            if (separator < 1 || separator == entry.length() - 1) {
                throw new IllegalArgumentException(
                        "Mirror state key ring must use keyId=base64Key entries");
            }
            String keyId = requiredKeyId(entry.substring(0, separator).trim());
            byte[] key;
            try {
                key = Base64.getDecoder().decode(
                        entry.substring(separator + 1).trim());
            } catch (IllegalArgumentException malformedBase64) {
                throw new IllegalArgumentException(
                        "Mirror state payload key is not valid base64: " + keyId);
            }
            if (parsed.putIfAbsent(keyId, key) != null) {
                throw new IllegalArgumentException(
                        "Duplicate mirror state payload key id: " + keyId);
            }
        }
        return parsed;
    }

    private static String requiredKeyId(String value) {
        String keyId = required(value, "keyId");
        if (!KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException(
                    "Mirror state payload key id is invalid");
        }
        return keyId;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private record ParsedEnvelope(
            String keyId, byte[] nonce, byte[] ciphertext) {
    }
}
