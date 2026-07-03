package com.leanowtech.bloge.gateway.visual.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
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
}
