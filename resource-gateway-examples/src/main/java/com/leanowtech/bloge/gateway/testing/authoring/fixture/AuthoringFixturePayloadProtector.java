package com.leanowtech.bloge.gateway.testing.authoring.fixture;

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
 * Rotation-aware AES-256-GCM envelope protection for authoring fixture payloads.
 */
public final class AuthoringFixturePayloadProtector {
    private static final String VERSION = "v1";
    private static final int AES_256_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Base64.Encoder ENCODER =
            Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final String activeKeyId;
    private final Map<String, SecretKeySpec> keys;
    private final SecureRandom random;

    public static AuthoringFixturePayloadProtector fromConfiguration(
            String activeKeyId, String configuredKeyRing) {
        return new AuthoringFixturePayloadProtector(
                activeKeyId, parseKeyRing(configuredKeyRing), new SecureRandom());
    }

    AuthoringFixturePayloadProtector(
            String activeKeyId, Map<String, byte[]> keys) {
        this(activeKeyId, keys, new SecureRandom());
    }

    AuthoringFixturePayloadProtector(
            String activeKeyId,
            Map<String, byte[]> keys,
            SecureRandom random) {
        this.activeKeyId = requiredKeyId(activeKeyId);
        Map<String, SecretKeySpec> validated = new LinkedHashMap<>();
        Objects.requireNonNull(keys, "keys").forEach((keyId, material) -> {
            String normalized = requiredKeyId(keyId);
            byte[] copied = Objects.requireNonNull(material, "key material").clone();
            if (copied.length != AES_256_BYTES) {
                throw new IllegalArgumentException(
                        "Authoring fixture payload keys must be 32-byte AES-256 keys");
            }
            if (validated.putIfAbsent(
                    normalized, new SecretKeySpec(copied, "AES")) != null) {
                throw new IllegalArgumentException(
                        "Duplicate authoring fixture payload key id");
            }
        });
        if (!validated.containsKey(this.activeKeyId)) {
            throw new IllegalArgumentException(
                    "Active authoring fixture key is absent from the key ring");
        }
        this.keys = Map.copyOf(validated);
        this.random = Objects.requireNonNull(random, "random");
    }

    public String protect(byte[] plaintext, String associatedData) {
        byte[] input = Objects.requireNonNull(plaintext, "plaintext").clone();
        if (input.length == 0) {
            throw new IllegalArgumentException("Fixture plaintext must not be empty");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] ciphertext = crypt(
                Cipher.ENCRYPT_MODE,
                keys.get(activeKeyId),
                nonce,
                required(associatedData, "associatedData"),
                input);
        return VERSION + "." + activeKeyId + "."
                + ENCODER.encodeToString(nonce) + "."
                + ENCODER.encodeToString(ciphertext);
    }

    public byte[] unprotect(String envelope, String associatedData) {
        ParsedEnvelope parsed = parse(envelope);
        SecretKeySpec key = keys.get(parsed.keyId());
        if (key == null) {
            throw new AuthoringFixtureIntegrityException();
        }
        return crypt(
                Cipher.DECRYPT_MODE,
                key,
                parsed.nonce(),
                required(associatedData, "associatedData"),
                parsed.ciphertext());
    }

    public boolean requiresRewrap(String envelope) {
        return !activeKeyId.equals(parse(envelope).keyId());
    }

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
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (AEADBadTagException authenticationFailure) {
            throw new AuthoringFixtureIntegrityException(authenticationFailure);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(
                    "Authoring fixture payload protection failed", failure);
        }
    }

    private static ParsedEnvelope parse(String envelope) {
        String[] parts = required(envelope, "envelope").split("\\.", 4);
        if (parts.length != 4
                || !VERSION.equals(parts[0])
                || !KEY_ID.matcher(parts[1]).matches()) {
            throw new AuthoringFixtureIntegrityException();
        }
        try {
            byte[] nonce = DECODER.decode(parts[2]);
            byte[] ciphertext = DECODER.decode(parts[3]);
            if (nonce.length != NONCE_BYTES
                    || ciphertext.length <= TAG_BITS / Byte.SIZE) {
                throw new AuthoringFixtureIntegrityException();
            }
            return new ParsedEnvelope(parts[1], nonce, ciphertext);
        } catch (IllegalArgumentException invalidBase64) {
            throw new AuthoringFixtureIntegrityException(invalidBase64);
        }
    }

    private static Map<String, byte[]> parseKeyRing(String value) {
        String configured = required(value, "configuredKeyRing");
        Map<String, byte[]> parsed = new LinkedHashMap<>();
        for (String entry : configured.split(",")) {
            int separator = entry.indexOf('=');
            if (separator < 1 || separator == entry.length() - 1) {
                throw new IllegalArgumentException(
                        "Authoring fixture key ring must use keyId=base64Key entries");
            }
            String keyId = requiredKeyId(entry.substring(0, separator).trim());
            byte[] material;
            try {
                material = Base64.getDecoder().decode(
                        entry.substring(separator + 1).trim());
            } catch (IllegalArgumentException invalidBase64) {
                throw new IllegalArgumentException(
                        "Authoring fixture key is not valid base64");
            }
            if (parsed.putIfAbsent(keyId, material) != null) {
                throw new IllegalArgumentException(
                        "Duplicate authoring fixture payload key id");
            }
        }
        return parsed;
    }

    private static String requiredKeyId(String value) {
        String normalized = required(value, "keyId");
        if (!KEY_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Authoring fixture key id is invalid");
        }
        return normalized;
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
