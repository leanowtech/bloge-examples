package com.leanowtech.bloge.gateway.testing.correctness.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonical business-content fingerprint boundary for correctness assets. */
public final class CorrectnessProtocolFingerprint {

    private static final int MAXIMUM_BYTES = 4 * 1024 * 1024;
    private static final Set<Class<?>> SUPPORTED_ASSETS = Set.of(
            CorrectnessDefinition.class,
            CoverageInventory.class,
            BusinessOracle.class,
            AssertionSet.class,
            ScenarioDraftSetV2.class,
            FixtureAssetDescriptor.class,
            CorrectnessPublication.class
    );
    private static final Set<String> SERVER_OWNED_FIELDS = Set.of(
            "createdAt", "updatedAt", "reviewedAt", "approvedAt", "displayName"
    );

    private CorrectnessProtocolFingerprint() {
    }

    /**
     * Hashes canonical business content while excluding mutable head revision and server metadata.
     * Exact nested reference revisions remain part of the hash.
     */
    public static String fingerprint(ObjectMapper mapper, Object asset) {
        if (mapper == null || asset == null || !SUPPORTED_ASSETS.contains(asset.getClass())) {
            throw new IllegalArgumentException("A supported correctness asset and mapper are required");
        }
        ObjectMapper canonicalMapper = mapper.copy()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JsonNode source = canonicalMapper.valueToTree(asset);
        if (!(source instanceof ObjectNode root)) {
            throw new IllegalArgumentException("Correctness asset must serialize as an object");
        }
        root.remove(List.of("revision", "metadata"));
        JsonNode canonical = canonicalNode(root);
        try {
            byte[] bytes = canonicalMapper.writeValueAsBytes(canonical);
            if (bytes.length > MAXIMUM_BYTES) {
                throw new IllegalArgumentException(
                        "Canonical correctness asset exceeds " + MAXIMUM_BYTES + " bytes");
            }
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Correctness asset cannot be canonically fingerprinted", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }

    private static JsonNode canonicalNode(JsonNode value) {
        if (value == null || value.isNull() || value.isValueNode()) return value;
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
                    .filter(entry -> !SERVER_OWNED_FIELDS.contains(entry.getKey()))
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(entry -> canonical.set(
                            entry.getKey(), canonicalNode(entry.getValue())));
            return canonical;
        }
        return value.deepCopy();
    }
}
