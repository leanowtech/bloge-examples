package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Canonical SHA-256 fingerprints for payload-free authoring documents. */
public final class AuthoringFingerprints {
    private static final ObjectMapper JSON = new ObjectMapper();
    private AuthoringFingerprints() { }

    /** Returns sha256: plus 64 lowercase hex digits for canonical JSON. */
    public static String of(JsonNode body) {
        if (body == null) throw new IllegalArgumentException("body is required");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical(body).toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("sha256:");
            for (byte b : digest) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException("unable to fingerprint authoring body", e); }
    }

    /** Canonicalizes object keys recursively while preserving array order. */
    public static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            List<String> keys = new ArrayList<>(); value.fieldNames().forEachRemaining(keys::add); keys.sort(String::compareTo);
            for (String key : keys) result.set(key, canonical(value.get(key)));
            return result;
        }
        if (value.isArray()) { ArrayNode result = JSON.createArrayNode(); value.forEach(item -> result.add(canonical(item))); return result; }
        return value.deepCopy();
    }
}
