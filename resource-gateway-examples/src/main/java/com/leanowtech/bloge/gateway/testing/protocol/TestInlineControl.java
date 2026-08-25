package com.leanowtech.bloge.gateway.testing.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of an inline control object.
 *
 * <p>The codec deliberately does not interpret its business fields. Accessors
 * return defensive tree copies so callers cannot mutate the parsed value. The
 * stored tree recursively sorts object keys by Java String natural order while
 * preserving array order.</p>
 */
public final class TestInlineControl {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode payload;
    private final String canonicalJson;

    TestInlineControl(JsonNode payload) {
        this.payload = canonicalize(Objects.requireNonNull(payload, "inline payload is required"));
        try {
            this.canonicalJson = MAPPER.writeValueAsString(this.payload);
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid inline payload");
        }
    }

    public JsonNode payload() {
        return payload.deepCopy();
    }

    private static JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) {
                sorted.set(name, canonicalize(value.get(name)));
            }
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode ordered = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : value) {
                ordered.add(canonicalize(element));
            }
            return ordered;
        }
        return value.deepCopy();
    }

    public String canonicalJson() {
        return canonicalJson;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TestInlineControl that && canonicalJson.equals(that.canonicalJson);
    }

    @Override
    public int hashCode() {
        return canonicalJson.hashCode();
    }

    @Override
    public String toString() {
        return "TestInlineControl{object}";
    }
}
