package com.leanowtech.bloge.gateway.testing.persistence;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Protects replayable worker-quarantine claim tokens with a rotation-aware AES-GCM envelope.
 *
 * <p>The first configured key is not inferred: callers name one active key and retain any prior
 * decrypt-only keys in the same key ring until every stored envelope has been rewrapped. Envelope
 * authentication also binds caller-supplied associated data, so moving ciphertext between command
 * rows fails closed.</p>
 */
public final class WorkerQuarantineClaimTokenProtector {

    private static final String VERSION = "v1";
    private static final int AES_256_BYTES = 32;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final String activeKeyId;
    private final Map<String, SecretKeySpec> keys;
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
        Map<String, SecretKeySpec> validated = new LinkedHashMap<>();
        keys.forEach((keyId, value) -> {
            String safeKeyId = requiredKeyId(keyId);
            byte[] key = Objects.requireNonNull(value, "key material").clone();
            if (key.length != AES_256_BYTES) {
                throw new IllegalArgumentException(
                        "Worker quarantine token keys must be 32-byte AES-256 keys");
            }
            if (validated.putIfAbsent(safeKeyId, new SecretKeySpec(key, "AES")) != null) {
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
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), nonce,
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
        SecretKeySpec key = keys.get(parsed.keyId());
        if (key == null) {
            throw new IllegalStateException(
                    "Worker quarantine claim token key is unavailable");
        }
        byte[] plaintext = crypt(Cipher.DECRYPT_MODE, key, parsed.nonce(),
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
}
