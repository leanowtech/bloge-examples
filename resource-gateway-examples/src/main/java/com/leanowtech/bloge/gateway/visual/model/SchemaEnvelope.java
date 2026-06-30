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
    private static final List<String> SCHEMA_ANNOTATION_KEYS = List.of(
            "$comment",
            "title",
            "description",
            "examples",
            "deprecated",
            "readOnly",
            "writeOnly"
    );
    private static final List<String> SCHEMA_DECLARATION_KEYS = List.of(
            "$defs"
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
        return flattenObjectAllOf(resolved);
    }

    private static Map<String, Object> flattenObjectAllOf(Map<String, Object> schema) {
        Object rawAllOf = schema.get("allOf");
        if (!(rawAllOf instanceof List<?> allOf) || allOf.isEmpty()) {
            return schema;
        }

        List<Map<String, Object>> fragments = new ArrayList<>();
        for (Object item : allOf) {
            if (!(item instanceof Map<?, ?> fragment) || !objectSchema(objectMap(fragment))) {
                return schema;
            }
            if (objectMap(fragment).keySet().stream().anyMatch(SCHEMA_DECLARATION_KEYS::contains)) {
                return schema;
            }
            fragments.add(objectMap(fragment));
        }

        Map<String, Object> sibling = new LinkedHashMap<>(schema);
        sibling.remove("allOf");
        if (sibling.keySet().stream().anyMatch(key -> !SCHEMA_ANNOTATION_KEYS.contains(key)
                && !SCHEMA_DECLARATION_KEYS.contains(key))) {
            if (!objectSchema(sibling)) {
                return schema;
            }
            fragments.add(sibling);
        }

        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("type", "object");
        Map<String, Object> mergedProperties = new LinkedHashMap<>();
        List<String> mergedRequired = new ArrayList<>();
        Map<String, Object> mergedPatternProperties = new LinkedHashMap<>();
        Map<String, Object> mergedDependentRequired = new LinkedHashMap<>();
        Map<String, Object> mergedDependentSchemas = new LinkedHashMap<>();
        Object additionalProperties = null;
        Object unevaluatedProperties = null;
        Object propertyNames = null;
        Long minProperties = null;
        Long maxProperties = null;

        for (Map<String, Object> fragment : fragments) {
            if (!mergeObjectKeyword(mergedProperties, fragment, "properties")
                    || !mergeObjectKeyword(mergedPatternProperties, fragment, "patternProperties")
                    || !mergeDependentRequiredKeyword(mergedDependentRequired, fragment)
                    || !mergeObjectKeyword(mergedDependentSchemas, fragment, "dependentSchemas")
                    || !mergeRequiredKeyword(mergedRequired, fragment)) {
                return schema;
            }

            Object nextAdditionalProperties = residualPolicy(fragment, "additionalProperties");
            additionalProperties = mergeResidualPolicy(additionalProperties, nextAdditionalProperties);
            if (additionalProperties == UnsupportedAllOfMerge.INSTANCE) {
                return schema;
            }
            Object nextUnevaluatedProperties = residualPolicy(fragment, "unevaluatedProperties");
            unevaluatedProperties = mergeResidualPolicy(unevaluatedProperties, nextUnevaluatedProperties);
            if (unevaluatedProperties == UnsupportedAllOfMerge.INSTANCE) {
                return schema;
            }

            if (fragment.containsKey("propertyNames")) {
                Object nextPropertyNames = fragment.get("propertyNames");
                if (!(nextPropertyNames instanceof Map<?, ?>)) {
                    return schema;
                }
                if (propertyNames != null && !propertyNames.equals(nextPropertyNames)) {
                    return schema;
                }
                propertyNames = deepCopyValue(nextPropertyNames);
            }
            Object nextMinProperties = propertyBound(fragment, "minProperties");
            if (nextMinProperties == UnsupportedAllOfMerge.INSTANCE) {
                return schema;
            }
            minProperties = maxLong(minProperties, (Long) nextMinProperties);
            Object nextMaxProperties = propertyBound(fragment, "maxProperties");
            if (nextMaxProperties == UnsupportedAllOfMerge.INSTANCE) {
                return schema;
            }
            maxProperties = minLong(maxProperties, (Long) nextMaxProperties);
        }

        if (!mergedProperties.isEmpty()) {
            merged.put("properties", mergedProperties);
        }
        if (!mergedRequired.isEmpty()) {
            merged.put("required", mergedRequired);
        }
        if (!mergedPatternProperties.isEmpty()) {
            merged.put("patternProperties", mergedPatternProperties);
        }
        if (!mergedDependentRequired.isEmpty()) {
            merged.put("dependentRequired", mergedDependentRequired);
        }
        if (!mergedDependentSchemas.isEmpty()) {
            merged.put("dependentSchemas", mergedDependentSchemas);
        }
        if (additionalProperties != null) {
            merged.put("additionalProperties", additionalProperties);
        }
        if (unevaluatedProperties != null) {
            merged.put("unevaluatedProperties", unevaluatedProperties);
        }
        if (propertyNames != null) {
            merged.put("propertyNames", propertyNames);
        }
        if (minProperties != null) {
            merged.put("minProperties", minProperties);
        }
        if (maxProperties != null) {
            merged.put("maxProperties", maxProperties);
        }
        for (String key : SCHEMA_ANNOTATION_KEYS) {
            if (sibling.containsKey(key)) {
                merged.put(key, deepCopyValue(sibling.get(key)));
            }
        }
        for (String key : SCHEMA_DECLARATION_KEYS) {
            if (sibling.containsKey(key)) {
                merged.put(key, deepCopyValue(sibling.get(key)));
            }
        }
        return merged;
    }

    private static boolean objectSchema(Map<String, Object> schema) {
        Object type = schema.get("type");
        return "object".equals(type)
                || type == null && (schema.containsKey("properties")
                || schema.containsKey("required")
                || schema.containsKey("additionalProperties")
                || schema.containsKey("unevaluatedProperties")
                || schema.containsKey("patternProperties")
                || schema.containsKey("propertyNames")
                || schema.containsKey("dependentRequired")
                || schema.containsKey("dependentSchemas")
                || schema.containsKey("minProperties")
                || schema.containsKey("maxProperties"));
    }

    private static boolean mergeProperties(Map<String, Object> merged, Map<String, Object> next) {
        if (next == null) {
            return true;
        }
        for (Map.Entry<String, Object> entry : next.entrySet()) {
            Object value = deepCopyValue(entry.getValue());
            if (merged.containsKey(entry.getKey()) && !merged.get(entry.getKey()).equals(value)) {
                return false;
            }
            merged.put(entry.getKey(), value);
        }
        return true;
    }

    private static boolean mergeObjectKeyword(Map<String, Object> merged,
                                              Map<String, Object> schema,
                                              String key) {
        if (!schema.containsKey(key)) {
            return true;
        }
        Object value = schema.get(key);
        return value instanceof Map<?, ?> map && mergeProperties(merged, objectMap(map));
    }

    private static boolean mergeDependentRequiredKeyword(Map<String, Object> merged, Map<String, Object> schema) {
        if (!schema.containsKey("dependentRequired")) {
            return true;
        }
        Object raw = schema.get("dependentRequired");
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return false;
        }
        Map<String, Object> next = objectMap(rawMap);
        for (Map.Entry<String, Object> entry : next.entrySet()) {
            if (!(entry.getValue() instanceof List<?> rawValues)) {
                return false;
            }
            List<String> values = new ArrayList<>();
            Object existing = merged.get(entry.getKey());
            if (existing instanceof List<?> existingValues) {
                for (Object item : existingValues) {
                    values.add(String.valueOf(item));
                }
            }
            List<String> seenInFragment = new ArrayList<>();
            for (Object item : rawValues) {
                if (!(item instanceof String value) || value.isBlank() || seenInFragment.contains(value)) {
                    return false;
                }
                seenInFragment.add(value);
                if (!values.contains(value)) {
                    values.add(value);
                }
            }
            merged.put(entry.getKey(), values);
        }
        return true;
    }

    private static boolean mergeRequiredKeyword(List<String> merged, Map<String, Object> schema) {
        if (!schema.containsKey("required")) {
            return true;
        }
        Object raw = schema.get("required");
        if (!(raw instanceof List<?> next)) {
            return false;
        }
        List<String> seenInFragment = new ArrayList<>();
        for (Object item : next) {
            if (!(item instanceof String value) || value.isBlank() || seenInFragment.contains(value)) {
                return false;
            }
            seenInFragment.add(value);
            if (!merged.contains(value)) {
                merged.add(value);
            }
        }
        return true;
    }

    private static Object residualPolicy(Map<String, Object> schema, String key) {
        if (!schema.containsKey(key)) {
            return null;
        }
        Object value = schema.get(key);
        if (Boolean.TRUE.equals(value) || Boolean.FALSE.equals(value)) {
            return value;
        }
        return UnsupportedAllOfMerge.INSTANCE;
    }

    private static Object mergeResidualPolicy(Object current, Object next) {
        if (next == null || Boolean.TRUE.equals(next)) {
            return current;
        }
        if (Boolean.FALSE.equals(next)) {
            return Boolean.FALSE;
        }
        return UnsupportedAllOfMerge.INSTANCE;
    }

    private static Object propertyBound(Map<String, Object> schema, String key) {
        if (!schema.containsKey(key)) {
            return null;
        }
        Object value = schema.get(key);
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long bound = ((Number) value).longValue();
            return bound >= 0 ? bound : UnsupportedAllOfMerge.INSTANCE;
        }
        return UnsupportedAllOfMerge.INSTANCE;
    }

    private static Long maxLong(Long current, Long next) {
        if (next == null) {
            return current;
        }
        return current == null ? next : Math.max(current, next);
    }

    private static Long minLong(Long current, Long next) {
        if (next == null) {
            return current;
        }
        return current == null ? next : Math.min(current, next);
    }

    private enum UnsupportedAllOfMerge {
        INSTANCE
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
