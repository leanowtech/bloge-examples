package com.leanowtech.bloge.gateway.visual.simulation;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, bounded JSON Schema sample-instance generator.
 *
 * <p>This is the synthesis engine behind the visual canvas mock-run (simulate) capability: given the
 * declared output schema of an operator that has no server implementation, it produces a value that
 * conforms to that schema so the graph can be executed end-to-end for runtime-correctness validation.</p>
 *
 * <p><b>Layered precedence</b> (per schema node, strongest first): {@code const} &gt; {@code default}
 * &gt; {@code examples[0]} &gt; {@code enum[0]} &gt; a type-based canonical value. Per-node author
 * fixtures take precedence over the whole generator and are applied by the caller, not here.</p>
 *
 * <p><b>Determinism.</b> No randomness is used; the same schema always yields the same value. This
 * keeps golden snapshots stable and mock runs reproducible.</p>
 *
 * <p><b>Safety.</b> Generation is bounded by {@link #MAX_DEPTH} and {@link #MAX_SAMPLE_NODES} to
 * defend against adversarial or pathological schemas (deep nesting, huge {@code minItems}). The
 * generator never throws on malformed schema input; it degrades to {@code null} or an empty container.</p>
 */
@Component
public class JsonSchemaSampleGenerator {

    /** Maximum schema nesting depth expanded before generation stops and yields {@code null}. */
    public static final int MAX_DEPTH = 12;

    /** Maximum number of value nodes synthesized for a single sample before generation stops. */
    public static final int MAX_SAMPLE_NODES = 512;

    /**
     * Upper bound on how many array items are synthesized for a single array, regardless of
     * {@code minItems}. Set high enough to preserve conformance for realistic schemas while still
     * bounding a single adversarial {@code minItems}; the overall {@link #MAX_SAMPLE_NODES} budget is
     * the primary defense against runaway generation.
     */
    private static final int MAX_ARRAY_ITEMS = 25;

    /**
     * Generates a schema-conforming sample value for a schema envelope.
     *
     * @param envelope the schema envelope, may be {@code null}
     * @return a value conforming to the schema, or {@code null} when the schema is absent/opaque
     */
    public Object generate(SchemaEnvelope envelope) {
        if (envelope == null) {
            return null;
        }
        return generate(envelope.schema());
    }

    /**
     * Generates a schema-conforming sample value for a raw JSON Schema map.
     *
     * @param schema the JSON Schema payload, may be {@code null}
     * @return a value conforming to the schema, or {@code null} when the schema is absent
     */
    public Object generate(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return null;
        }
        return generateValue(schema, 0, new int[]{MAX_SAMPLE_NODES});
    }

    private Object generateValue(Object rawSchema, int depth, int[] nodeBudget) {
        if (depth > MAX_DEPTH || nodeBudget[0] <= 0) {
            return null;
        }
        if (!(rawSchema instanceof Map<?, ?> rawMap)) {
            return null;
        }
        nodeBudget[0]--;
        Map<String, Object> schema = asStringMap(rawMap);

        // Layer 1..4: explicit values declared on the schema, strongest first.
        if (schema.containsKey("const")) {
            return deepCopy(schema.get("const"));
        }
        if (schema.containsKey("default")) {
            return deepCopy(schema.get("default"));
        }
        Object exampleValue = firstOf(schema.get("examples"));
        if (exampleValue != null) {
            return deepCopy(exampleValue);
        }
        Object enumValue = firstOf(schema.get("enum"));
        if (enumValue != null) {
            return deepCopy(enumValue);
        }

        // Combinators: prefer the first branch of a union; allOf is normally pre-flattened by
        // SchemaEnvelope but is handled defensively for nested subschemas.
        Object combinator = firstOf(schema.get("oneOf"));
        if (combinator == null) {
            combinator = firstOf(schema.get("anyOf"));
        }
        if (combinator != null) {
            return generateValue(combinator, depth, nodeBudget);
        }
        Object allOfBranch = firstOf(schema.get("allOf"));
        if (allOfBranch != null && !schema.containsKey("type") && !schema.containsKey("properties")) {
            return generateValue(allOfBranch, depth, nodeBudget);
        }

        // Layer 5: type-based canonical value.
        return generateByType(effectiveType(schema), schema, depth, nodeBudget);
    }

    private Object generateByType(String type, Map<String, Object> schema, int depth, int[] nodeBudget) {
        return switch (type) {
            case "object" -> generateObject(schema, depth, nodeBudget);
            case "array" -> generateArray(schema, depth, nodeBudget);
            case "string" -> canonicalString(schema);
            case "integer" -> canonicalInteger(schema);
            case "number" -> canonicalNumber(schema);
            case "boolean" -> Boolean.FALSE;
            case "null" -> null;
            default -> null;
        };
    }

    private Object generateObject(Map<String, Object> schema, int depth, int[] nodeBudget) {
        Map<String, Object> instance = new LinkedHashMap<>();
        Object rawProperties = schema.get("properties");
        if (rawProperties instanceof Map<?, ?> propertyMap) {
            for (Map.Entry<?, ?> entry : propertyMap.entrySet()) {
                if (nodeBudget[0] <= 0) {
                    break;
                }
                String name = String.valueOf(entry.getKey());
                instance.put(name, generateValue(entry.getValue(), depth + 1, nodeBudget));
            }
        }
        // Ensure required fields not covered by declared properties are still present.
        for (Object required : asList(schema.get("required"))) {
            String name = String.valueOf(required);
            if (!instance.containsKey(name)) {
                instance.put(name, null);
            }
        }
        return instance;
    }

    private Object generateArray(Map<String, Object> schema, int depth, int[] nodeBudget) {
        Object items = schema.get("items");
        Object itemSchema = items instanceof Map<?, ?> ? items : firstOf(schema.get("prefixItems"));
        int minItems = intValue(schema.get("minItems"), 0);
        int count = itemSchema == null
                ? Math.min(minItems, MAX_ARRAY_ITEMS)
                : Math.min(Math.max(minItems, 1), MAX_ARRAY_ITEMS);
        List<Object> instance = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (nodeBudget[0] <= 0) {
                break;
            }
            instance.add(itemSchema == null ? null : generateValue(itemSchema, depth + 1, nodeBudget));
        }
        return instance;
    }

    private String canonicalString(Map<String, Object> schema) {
        String format = stringValue(schema.get("format"));
        String base = switch (format) {
            case "date-time" -> "1970-01-01T00:00:00Z";
            case "date" -> "1970-01-01";
            case "time" -> "00:00:00Z";
            case "email" -> "user@example.com";
            case "uri", "url", "iri" -> "https://example.com";
            case "uuid" -> "00000000-0000-0000-0000-000000000000";
            case "hostname" -> "example.com";
            case "ipv4" -> "127.0.0.1";
            default -> "string";
        };
        int minLength = intValue(schema.get("minLength"), 0);
        int maxLength = intValue(schema.get("maxLength"), Integer.MAX_VALUE);
        StringBuilder value = new StringBuilder(base);
        while (value.length() < minLength) {
            value.append('x');
        }
        if (value.length() > maxLength) {
            value.setLength(Math.max(maxLength, 0));
        }
        return value.toString();
    }

    private Object canonicalInteger(Map<String, Object> schema) {
        long value = longValue(schema.get("minimum"), longValue(schema.get("exclusiveMinimum"), 0L));
        if (schema.get("exclusiveMinimum") instanceof Number exclusive && value <= exclusive.longValue()) {
            value = exclusive.longValue() + 1;
        }
        long maximum = longValue(schema.get("maximum"), Long.MAX_VALUE);
        return Math.min(value, maximum);
    }

    private Object canonicalNumber(Map<String, Object> schema) {
        if (schema.get("minimum") instanceof Number minimum) {
            return minimum.doubleValue();
        }
        if (schema.get("exclusiveMinimum") instanceof Number exclusive) {
            return exclusive.doubleValue() + 1;
        }
        return 0.0d;
    }

    private String effectiveType(Map<String, Object> schema) {
        Object type = schema.get("type");
        if (type instanceof String typeString && !typeString.isBlank()) {
            return typeString;
        }
        if (type instanceof List<?> typeList) {
            for (Object candidate : typeList) {
                if (candidate instanceof String candidateString && !candidateString.isBlank()
                        && !"null".equals(candidateString)) {
                    return candidateString;
                }
            }
        }
        // Infer object/array from structural keywords when the type is omitted.
        if (schema.containsKey("properties") || schema.containsKey("required")
                || schema.containsKey("additionalProperties") || schema.containsKey("patternProperties")) {
            return "object";
        }
        if (schema.containsKey("items") || schema.containsKey("prefixItems")) {
            return "array";
        }
        return "";
    }

    private static Object firstOf(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            return list.get(0);
        }
        return null;
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static Map<String, Object> asStringMap(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static long longValue(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), deepCopy(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return value;
    }
}
