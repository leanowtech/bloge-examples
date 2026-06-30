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
    private static final String DEFS_POINTER_PREFIX = "#/$defs/";
    private static final List<String> REF_ANNOTATION_KEYS = List.of(
            "$ref",
            "$comment",
            "title",
            "description",
            "examples",
            "deprecated",
            "readOnly",
            "writeOnly"
    );

    /**
     * Creates a schema envelope.
     */
    public SchemaEnvelope {
        format = format == null || format.isBlank() ? JSON_SCHEMA : format;
        version = version == null || version.isBlank() ? "2020-12" : version;
        schema = schema == null ? Map.of() : resolveLocalReferences(deepCopy(schema));
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

    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, deepCopyValue(value)));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), deepCopyValue(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(deepCopyValue(item));
            }
            return copy;
        }
        return value;
    }

    private static Map<String, Object> resolveLocalReferences(Map<String, Object> root) {
        Object resolved = resolveLocalReferences(root, root, new ArrayList<>());
        return resolved instanceof Map<?, ?> map ? objectMap(map) : root;
    }

    private static Object resolveLocalReferences(Object value,
                                                 Map<String, Object> root,
                                                 List<String> referenceStack) {
        if (value instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>(list.size());
            for (Object item : list) {
                resolved.add(resolveLocalReferences(item, root, referenceStack));
            }
            return resolved;
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            return value;
        }

        Map<String, Object> map = objectMap(rawMap);
        String ref = expandableLocalRef(map);
        if (ref != null) {
            if (referenceStack.contains(ref)) {
                return map;
            }
            Object target = resolveJsonPointer(root, ref);
            if (!(target instanceof Map<?, ?> targetMap)) {
                return map;
            }
            referenceStack.add(ref);
            Object resolvedTarget = resolveLocalReferences(deepCopyValue(targetMap), root, referenceStack);
            referenceStack.remove(referenceStack.size() - 1);
            if (!(resolvedTarget instanceof Map<?, ?> resolvedTargetMap)) {
                return map;
            }
            Map<String, Object> merged = objectMap(resolvedTargetMap);
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!"$ref".equals(entry.getKey())) {
                    merged.put(entry.getKey(), deepCopyValue(entry.getValue()));
                }
            }
            return merged;
        }

        Map<String, Object> resolved = new LinkedHashMap<>();
        map.forEach((key, item) -> resolved.put(key, resolveLocalReferences(item, root, referenceStack)));
        return resolved;
    }

    private static String expandableLocalRef(Map<String, Object> schema) {
        Object raw = schema.get("$ref");
        if (!(raw instanceof String ref) || !ref.startsWith(DEFS_POINTER_PREFIX)) {
            return null;
        }
        for (String key : schema.keySet()) {
            if (!REF_ANNOTATION_KEYS.contains(key)) {
                return null;
            }
        }
        return ref;
    }

    private static Object resolveJsonPointer(Map<String, Object> root, String ref) {
        if (!ref.startsWith("#/")) {
            return null;
        }
        Object current = root;
        String[] tokens = ref.substring(2).split("/");
        for (String token : tokens) {
            String key = decodeJsonPointerToken(token);
            if (current instanceof Map<?, ?> map) {
                current = map.get(key);
            } else if (current instanceof List<?> list) {
                Integer index = listIndex(key);
                if (index == null || index >= list.size()) {
                    return null;
                }
                current = list.get(index);
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static String decodeJsonPointerToken(String token) {
        return token.replace("~1", "/").replace("~0", "~");
    }

    private static Integer listIndex(String value) {
        try {
            int index = Integer.parseInt(value);
            return index < 0 ? null : index;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Map<String, Object> objectMap(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }
}
