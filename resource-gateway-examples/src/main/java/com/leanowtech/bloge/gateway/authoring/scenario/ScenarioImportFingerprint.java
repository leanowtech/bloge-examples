package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Canonical fingerprint boundary owned by the Scenario import protocol. */
final class ScenarioImportFingerprint {

    private ScenarioImportFingerprint() {
    }

    static String of(ObjectMapper mapper, Object value, int maximumBytes) {
        if (mapper == null || maximumBytes < 1) {
            throw new IllegalArgumentException("Scenario import fingerprint inputs are required");
        }
        try {
            byte[] body = mapper.writeValueAsBytes(canonicalNode(mapper.valueToTree(value)));
            if (body.length > maximumBytes) {
                throw new IllegalArgumentException("Canonical Scenario import value exceeds "
                        + maximumBytes + " bytes");
            }
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(body));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Scenario import value cannot be fingerprinted", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }

    private static JsonNode canonicalNode(JsonNode value) {
        if (value == null || value.isNull() || value.isValueNode()) {
            return value;
        }
        if (value.isArray()) {
            ArrayNode canonical = JsonNodeFactory.instance.arrayNode();
            value.forEach(entry -> canonical.add(canonicalNode(entry)));
            return canonical;
        }
        if (value.isObject()) {
            ObjectNode canonical = JsonNodeFactory.instance.objectNode();
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            value.fields().forEachRemaining(fields::add);
            fields.stream()
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(entry -> canonical.set(entry.getKey(), canonicalNode(entry.getValue())));
            return canonical;
        }
        return value.deepCopy();
    }
}
