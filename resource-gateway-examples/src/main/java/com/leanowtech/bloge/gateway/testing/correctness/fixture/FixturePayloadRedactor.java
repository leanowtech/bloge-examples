package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RedactionDescriptor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded JSON redaction policy shared by Fixture material write paths. */
final class FixturePayloadRedactor {

    static final int MAX_PAYLOAD_BYTES = 1_048_576;
    static final int MAX_DEPTH = 64;
    static final int MAX_NODES = 20_000;
    static final int MAX_REDACTION_PATHS = 256;
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passwd|secret|token|authorization|credential|cookie|api[-_]?key).*" );

    private final ObjectMapper mapper;

    FixturePayloadRedactor(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    Result redact(Object payload, RedactionDescriptor requested) {
        if (payload == null || requested == null) {
            throw invalid("Fixture payload and redaction descriptor are required");
        }
        JsonNode root = mapper.valueToTree(payload);
        byte[] source = bytes(root);
        if (source.length > MAX_PAYLOAD_BYTES) {
            throw invalid("Fixture payload exceeds the one MiB limit");
        }
        validateShape(root, 0, new int[]{0});
        Set<String> applied = new LinkedHashSet<>();
        for (String path : requested.redactedPaths()) {
            applyExplicit(root, path);
            applied.add(path);
        }
        redactSensitiveKeys(root, "", applied);
        if (applied.size() > MAX_REDACTION_PATHS) {
            throw invalid("Fixture redaction path limit exceeded");
        }
        RedactionDescriptor actual = new RedactionDescriptor(
                requested.profileVersion(), List.copyOf(applied), requested.reviewed());
        try {
            return new Result(mapper.treeToValue(root, Object.class), actual);
        } catch (JsonProcessingException failure) {
            throw invalid("Fixture payload could not be normalized");
        }
    }

    private void applyExplicit(JsonNode root, String path) {
        if (path == null || path.isBlank() || !path.startsWith("/") || "/".equals(path)) {
            throw invalid("Fixture redaction path must be a non-root JSON Pointer");
        }
        JsonPointer pointer;
        try {
            pointer = JsonPointer.compile(path);
        } catch (IllegalArgumentException invalidPointer) {
            throw invalid("Fixture redaction path is not a valid JSON Pointer");
        }
        JsonPointer parentPointer = pointer.head();
        JsonNode parent = root.at(parentPointer);
        if (parent.isMissingNode()) {
            throw invalid("Fixture redaction path does not exist");
        }
        String property = pointer.last().getMatchingProperty();
        int index = pointer.last().getMatchingIndex();
        if (parent instanceof ObjectNode object && object.has(property)) {
            object.set(property, TextNode.valueOf("[REDACTED]"));
            return;
        }
        if (parent instanceof ArrayNode array && index >= 0 && index < array.size()) {
            array.set(index, TextNode.valueOf("[REDACTED]"));
            return;
        }
        throw invalid("Fixture redaction path does not exist");
    }

    private void redactSensitiveKeys(JsonNode node, String path, Set<String> applied) {
        if (node instanceof ObjectNode object) {
            List<String> fields = new ArrayList<>();
            object.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                String childPath = path + "/" + escape(field);
                if (SENSITIVE_KEY.matcher(field).matches()) {
                    object.set(field, TextNode.valueOf("[REDACTED]"));
                    applied.add(childPath);
                } else {
                    redactSensitiveKeys(object.get(field), childPath, applied);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                redactSensitiveKeys(array.get(index), path + "/" + index, applied);
            }
        }
    }

    private void validateShape(JsonNode node, int depth, int[] count) {
        if (depth > MAX_DEPTH || ++count[0] > MAX_NODES) {
            throw invalid("Fixture payload complexity limit exceeded");
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) validateShape(fields.next().getValue(), depth + 1, count);
        } else if (node.isArray()) {
            for (JsonNode child : node) validateShape(child, depth + 1, count);
        }
    }

    private byte[] bytes(JsonNode value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException failure) {
            throw invalid("Fixture payload could not be encoded");
        }
    }

    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private static FixtureMaterialCommandException invalid(String message) {
        return new FixtureMaterialCommandException(
                422, "RG.CORRECTNESS.FIXTURE_PAYLOAD_INVALID", message);
    }

    record Result(Object payload, RedactionDescriptor redaction) {
    }
}
