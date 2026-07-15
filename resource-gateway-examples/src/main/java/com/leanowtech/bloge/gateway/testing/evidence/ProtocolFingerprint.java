package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Canonical SHA-256 fingerprints for frozen test targets, fixtures, plans, and bindings. */
public final class ProtocolFingerprint {

    private ProtocolFingerprint() {
    }

    /**
     * Canonically serializes a protocol value and returns a prefixed SHA-256 fingerprint.
     *
     * @param mapper application JSON mapper used as the type/annotation baseline
     * @param value protocol value
     * @return {@code sha256:<hex>}
     */
    public static String of(ObjectMapper mapper, Object value) {
        return sha256(canonicalBytes(mapper, value));
    }

    /**
     * Canonically fingerprints a protocol value while enforcing the encoded size on the same
     * immutable byte sequence that is hashed.
     *
     * @param mapper application JSON mapper
     * @param value protocol value
     * @param maximumBytes positive canonical byte limit
     * @return {@code sha256:<hex>}
     */
    public static String ofBounded(ObjectMapper mapper, Object value, int maximumBytes) {
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        byte[] canonical = canonicalBytes(mapper, value);
        if (canonical.length > maximumBytes) {
            throw new IllegalArgumentException("Canonical protocol value exceeds "
                    + maximumBytes + " bytes");
        }
        return sha256(canonical);
    }

    private static byte[] canonicalBytes(ObjectMapper mapper, Object value) {
        ObjectMapper canonical = mapper.copy()
                .registerModule(new JavaTimeModule())
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        canonical.setConfig(canonical.getSerializationConfig()
                .with(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY));
        try {
            return canonical.writeValueAsBytes(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Protocol value cannot be fingerprinted", ex);
        }
    }

    /** @return a prefixed SHA-256 fingerprint for UTF-8 text */
    public static String ofText(String value) {
        return sha256((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns a prefixed SHA-256 fingerprint for an immutable binary artifact.
     *
     * @param value binary artifact bytes; {@code null} is treated as an empty artifact
     * @return {@code sha256:<hex>}
     */
    public static String ofBytes(byte[] value) {
        return sha256(value == null ? new byte[0] : value);
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM does not provide SHA-256", ex);
        }
    }
}
