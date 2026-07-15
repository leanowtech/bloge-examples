package com.leanowtech.bloge.gateway.visual.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Computes stable fingerprints for portable visual control-plane bundles.
 */
public final class VisualBundleFingerprint {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private VisualBundleFingerprint() {
    }

    /**
     * Computes a SHA-256 fingerprint from canonical JSON material.
     *
     * @param material fingerprint material
     * @return stable sha256 fingerprint
     */
    public static String fromMaterial(Map<String, Object> material) {
        try {
            byte[] body = MAPPER.writeValueAsBytes(material == null ? Map.of() : material);
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to fingerprint visual bundle material", e);
        }
    }

    /**
     * Canonically fingerprints a versioned visual protocol value with an encoded-size bound.
     *
     * <p>This method sorts record/object properties as well as map entries. Existing visual bundle
     * fingerprints retain {@link #fromMaterial(Map)} semantics.</p>
     *
     * @param mapper application mapper used as the annotation and type baseline
     * @param value immutable protocol value
     * @param maximumBytes positive canonical JSON byte limit
     * @return stable {@code sha256:<lowercase-hex>} fingerprint
     */
    public static String fromCanonicalValue(ObjectMapper mapper, Object value, int maximumBytes) {
        if (mapper == null || maximumBytes < 1) {
            throw new IllegalArgumentException("Canonical fingerprint mapper and byte limit are required");
        }
        ObjectMapper canonical = mapper.copy()
                .registerModule(new JavaTimeModule())
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        canonical.setConfig(canonical.getSerializationConfig()
                .with(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY));
        try {
            byte[] body = canonical.writeValueAsBytes(value);
            if (body.length > maximumBytes) {
                throw new IllegalArgumentException("Canonical visual protocol value exceeds "
                        + maximumBytes + " bytes");
            }
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(body));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Visual protocol value cannot be fingerprinted", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not provide SHA-256", e);
        }
    }
}
