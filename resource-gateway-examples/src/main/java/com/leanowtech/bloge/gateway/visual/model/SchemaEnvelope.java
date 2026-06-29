package com.leanowtech.bloge.gateway.visual.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight JSON-schema envelope used by the visual authoring APIs.
 *
 * @param format schema format, currently {@code json-schema}
 * @param version schema dialect/version label
 * @param schema schema payload
 */
public record SchemaEnvelope(
        String format,
        String version,
        Map<String, Object> schema
) {
    public static final String JSON_SCHEMA = "json-schema";

    /**
     * Creates a schema envelope.
     */
    public SchemaEnvelope {
        format = format == null || format.isBlank() ? JSON_SCHEMA : format;
        version = version == null || version.isBlank() ? "2020-12" : version;
        schema = schema == null ? Map.of() : deepCopy(schema);
    }

    /**
     * @return an unconstrained schema
     */
    public static SchemaEnvelope opaque() {
        return new SchemaEnvelope(JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "additionalProperties", true
        ));
    }

    /**
     * Builds an object schema.
     *
     * @param properties object properties
     * @param required required property names
     * @return schema envelope
     */
    public static SchemaEnvelope object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "object");
        body.put("properties", properties == null ? Map.of() : new LinkedHashMap<>(properties));
        body.put("required", required == null ? List.of() : List.copyOf(required));
        body.put("additionalProperties", false);
        return new SchemaEnvelope(JSON_SCHEMA, "2020-12", body);
    }

    /**
     * @return object properties when present
     */
    public Map<String, Object> properties() {
        Object raw = schema.get("properties");
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> properties.put(String.valueOf(key), value));
        return properties;
    }

    /**
     * @return required property names when present
     */
    public List<String> required() {
        Object raw = schema.get("required");
        if (!(raw instanceof List<?> rawList)) {
            return List.of();
        }
        List<String> required = new ArrayList<>();
        for (Object item : rawList) {
            if (item != null) {
                required.add(String.valueOf(item));
            }
        }
        return required;
    }

    /**
     * @param name property name
     * @return true when the schema defines the property
     */
    public boolean hasProperty(String name) {
        return properties().containsKey(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (value instanceof Map<?, ?> map) {
                copy.put(key, deepCopy((Map<String, Object>) map));
            } else if (value instanceof List<?> list) {
                copy.put(key, List.copyOf(list));
            } else {
                copy.put(key, value);
            }
        });
        return copy;
    }
}
