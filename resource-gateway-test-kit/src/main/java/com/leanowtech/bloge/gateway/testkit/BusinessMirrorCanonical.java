package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Shared bounded JSON canonicalization used by independent Business Mirror verifiers. */
final class BusinessMirrorCanonical {
    private static final ObjectMapper JSON = new ObjectMapper();

    private BusinessMirrorCanonical() {
    }

    static String fingerprint(JsonNode value, String tooLargeCode, String failureCode) {
        try {
            byte[] canonical = JSON.writeValueAsBytes(canonical(value));
            if (canonical.length > BusinessMirrorAuthoringVerifier.MAXIMUM_DRAFT_BYTES) {
                throw new IllegalArgumentException(tooLargeCode);
            }
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | GeneralSecurityException failure) {
            throw new IllegalArgumentException(failureCode);
        }
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> sorted.set(name, canonical(value.get(name))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value.deepCopy();
    }
}
