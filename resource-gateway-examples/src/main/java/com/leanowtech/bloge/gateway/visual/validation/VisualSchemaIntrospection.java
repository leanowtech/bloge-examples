package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Shared JSON Schema navigation helpers used by visual authoring read models.
 */
public final class VisualSchemaIntrospection {

    private static final Pattern ARRAY_INDEX = Pattern.compile("\\d+");

    private VisualSchemaIntrospection() {
    }

    /**
     * @param envelope schema envelope
     * @param maxPaths maximum paths to return
     * @param maxDepth maximum nested traversal depth
     * @return stable field paths that can participate in canvas connections
     */
    public static List<String> connectableSchemaPaths(SchemaEnvelope envelope, int maxPaths, int maxDepth) {
        return connectableSchemaPaths(envelope == null ? Map.of() : envelope.schema(), maxPaths, maxDepth);
    }

    /**
     * @param schema JSON Schema object
     * @param maxPaths maximum paths to return
     * @param maxDepth maximum nested traversal depth
     * @return stable field paths that can participate in canvas connections
     */
    public static List<String> connectableSchemaPaths(Map<String, Object> schema, int maxPaths, int maxDepth) {
        List<String> paths = new ArrayList<>();
        collectConnectableSchemaPaths(schema == null ? Map.of() : schema, "", paths, 0, maxDepth);
        return paths.stream().distinct().limit(maxPaths).toList();
    }

    /**
     * @param schema JSON Schema object
     * @param path dot-separated canvas path
     * @return schema at path, or null when the path is not declared or allowed
     */
    public static Map<String, Object> schemaAtPath(Map<String, Object> schema, String path) {
        if (path == null || path.isBlank()) {
            return schema;
        }
        Map<String, Object> current = schema == null ? Map.of() : schema;
        for (String segment : path.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            if ("array".equals(schemaType(current))) {
                Integer index = arrayIndexSegment(segment);
                if (index == null) {
                    return null;
                }
                Map<String, Object> item = arrayItemSchemaForIndex(current, index);
                if (item == null) {
                    return null;
                }
                current = item;
                continue;
            }
            Map<String, Object> next = objectSchema(propertiesOf(current).get(segment));
            if (next == null) {
                if (!propertyNameAllowedBySchema(current, segment)) {
                    return null;
                }
                next = patternPropertySchema(current, segment);
            }
            if (next == null) {
                next = additionalPropertySchema(current);
            }
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current;
    }

    /**
     * @param schema JSON Schema object
     * @return declared or structurally inferred schema type used for path traversal
     */
    public static String schemaType(Map<String, Object> schema) {
        if (schema == null) {
            return "";
        }
        Object type = schema.get("kind");
        if (type == null) {
            type = schema.get("type");
        }
        if (type instanceof List<?> types) {
            return nullableTypePrimary(types);
        }
        if (type == null && hasSchemaKeyword(schema, "properties", "required", "additionalProperties",
                "unevaluatedProperties", "patternProperties", "propertyNames", "dependentRequired",
                "dependentSchemas", "minProperties", "maxProperties")) {
            return "object";
        }
        if (type == null && hasSchemaKeyword(schema, "items", "prefixItems", "unevaluatedItems", "contains",
                "minItems", "maxItems", "uniqueItems", "minContains", "maxContains")) {
            return "array";
        }
        if (type == null && schema.containsKey("const")) {
            return schemaTypeForValue(schema.get("const"));
        }
        return type == null ? "" : String.valueOf(type);
    }

    /**
     * @param schema JSON Schema object
     * @param keywords JSON Schema keywords
     * @return true when the schema contains at least one keyword
     */
    public static boolean hasSchemaKeyword(Map<String, Object> schema, String... keywords) {
        if (schema == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (schema.containsKey(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param raw raw JSON value
     * @return copied schema map, or null when the raw value is not an object schema
     */
    public static Map<String, Object> objectSchema(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        map.forEach((key, value) -> schema.put(String.valueOf(key), value));
        return schema;
    }

    /**
     * @param schema JSON Schema object
     * @return object property schemas keyed by property name
     */
    public static Map<String, Object> propertiesOf(Map<String, Object> schema) {
        if (schema == null) {
            return Map.of();
        }
        return propertiesMap(schema.get("properties"));
    }

    /**
     * @param schema JSON Schema object
     * @param index array item index
     * @return tuple item schema or uniform item schema for the index
     */
    public static Map<String, Object> arrayItemSchemaForIndex(Map<String, Object> schema, int index) {
        Object prefixItems = schema == null ? null : schema.get("prefixItems");
        if (prefixItems instanceof List<?> list && index < list.size()) {
            return objectSchema(list.get(index));
        }
        return objectSchema(schema == null ? null : schema.get("items"));
    }

    /**
     * @param schema JSON Schema object
     * @return copied prefix item schema list
     */
    public static List<Map<String, Object>> prefixItemsOf(Map<String, Object> schema) {
        Object raw = schema == null ? null : schema.get("prefixItems");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> prefixItems = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> itemSchema = objectSchema(item);
            if (itemSchema != null) {
                prefixItems.add(itemSchema);
            }
        }
        return prefixItems;
    }

    private static void collectConnectableSchemaPaths(Map<String, Object> schema,
                                                      String path,
                                                      List<String> paths,
                                                      int depth,
                                                      int maxDepth) {
        if (schema == null) {
            return;
        }
        paths.add(path);
        if (depth >= maxDepth || schema.containsKey("oneOf") || schema.containsKey("anyOf")) {
            return;
        }
        if ("array".equals(schemaType(schema))) {
            List<Map<String, Object>> prefixItems = prefixItemsOf(schema);
            for (int i = 0; i < prefixItems.size(); i++) {
                collectConnectableSchemaPaths(prefixItems.get(i), appendPath(path, String.valueOf(i)),
                        paths, depth + 1, maxDepth);
            }
            Map<String, Object> items = objectSchema(schema.get("items"));
            if (items != null) {
                int representativeIndex = prefixItems.isEmpty() ? 0 : prefixItems.size();
                collectConnectableSchemaPaths(items, appendPath(path, String.valueOf(representativeIndex)),
                        paths, depth + 1, maxDepth);
            }
            return;
        }
        for (Map.Entry<String, Object> entry : propertiesOf(schema).entrySet()) {
            Map<String, Object> child = objectSchema(entry.getValue());
            if (child != null) {
                collectConnectableSchemaPaths(child, appendPath(path, entry.getKey()), paths, depth + 1, maxDepth);
            }
        }
    }

    private static String appendPath(String prefix, String segment) {
        String safeSegment = segment == null ? "" : segment.trim();
        if (safeSegment.isBlank()) {
            return prefix == null ? "" : prefix;
        }
        if (prefix == null || prefix.isBlank()) {
            return safeSegment;
        }
        return prefix + "." + safeSegment;
    }

    /**
     * @param segment dot-path segment
     * @return non-negative array index, or null when the segment is not canonical array syntax
     */
    public static Integer arrayIndexSegment(String segment) {
        if (!ARRAY_INDEX.matcher(segment).matches()) {
            return null;
        }
        try {
            int index = Integer.parseInt(segment);
            return index < 0 ? null : index;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean propertyNameAllowedBySchema(Map<String, Object> schema, String propertyName) {
        Map<String, Object> propertyNameSchema = objectSchema(schema == null ? null : schema.get("propertyNames"));
        if (propertyNameSchema == null) {
            return true;
        }
        Map<String, Object> effectiveSchema = new LinkedHashMap<>(propertyNameSchema);
        if (!effectiveSchema.containsKey("type") && !effectiveSchema.containsKey("kind")) {
            effectiveSchema.put("type", "string");
        }
        return VisualSchemaCompatibility.valueMatchesSchema(propertyName, effectiveSchema);
    }

    private static Map<String, Object> patternPropertySchema(Map<String, Object> schema, String propertyName) {
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map.Entry<String, Object> entry : propertiesMap(schema == null ? null : schema.get("patternProperties"))
                .entrySet()) {
            try {
                if (Pattern.compile(entry.getKey()).matcher(propertyName).find()) {
                    Map<String, Object> candidate = objectSchema(entry.getValue());
                    if (candidate != null) {
                        matches.add(candidate);
                    }
                }
            } catch (PatternSyntaxException ignored) {
                return null;
            }
        }
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static Map<String, Object> additionalPropertySchema(Map<String, Object> schema) {
        Object residual = residualPropertiesPolicy(schema);
        if (Boolean.TRUE.equals(residual)) {
            return Map.of();
        }
        return objectSchema(residual);
    }

    private static Object residualPropertiesPolicy(Map<String, Object> schema) {
        if (schema == null) {
            return null;
        }
        if (schema.containsKey("additionalProperties")) {
            return schema.get("additionalProperties");
        }
        return schema.get("unevaluatedProperties");
    }

    private static Map<String, Object> propertiesMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        map.forEach((key, value) -> properties.put(String.valueOf(key), value));
        return properties;
    }

    private static String nullableTypePrimary(List<?> types) {
        String primary = "";
        int concreteTypes = 0;
        for (Object item : types) {
            if (!(item instanceof String type) || type.isBlank()) {
                return String.valueOf(types);
            }
            if (!"null".equals(type)) {
                primary = type;
                concreteTypes++;
            }
        }
        if (concreteTypes > 1) {
            return String.valueOf(types);
        }
        return primary.isBlank() ? "null" : primary;
    }

    private static String schemaTypeForValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (isIntegerValue(value)) {
            return "integer";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        return "";
    }

    private static boolean isIntegerValue(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return true;
        }
        if (value instanceof java.math.BigInteger) {
            return true;
        }
        if (value instanceof Float || value instanceof Double || value instanceof java.math.BigDecimal) {
            double number = ((Number) value).doubleValue();
            return Double.isFinite(number) && Math.rint(number) == number;
        }
        return false;
    }
}
