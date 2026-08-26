package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Package-private canonical JSON hashing shared by strict protocol consumers. */
final class ProtocolCanonical {
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

    private ProtocolCanonical() {
    }

    static String fingerprint(Object value) {
        return fingerprint(JSON, value);
    }

    private static String fingerprint(ObjectMapper mapper, Object value) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(value);
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid protocol fingerprint material");
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }
}
