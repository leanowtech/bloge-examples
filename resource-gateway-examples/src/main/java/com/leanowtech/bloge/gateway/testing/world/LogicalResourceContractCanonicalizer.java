package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class LogicalResourceContractCanonicalizer {
    private static final Set<String> SET_LIKE_ARRAY_KEYS = Set.of("enum", "required", "type");

    private LogicalResourceContractCanonicalizer() {
    }

    static SchemaEnvelope canonicalSchema(SchemaEnvelope envelope) {
        if (envelope == null || !SchemaEnvelope.JSON_SCHEMA.equals(envelope.format())
                || !"2020-12".equals(envelope.version()) || envelope.schema().isEmpty()) {
            throw LogicalResourceContractException.invalid();
        }
        Object canonical = canonicalValue(envelope.schema(), "", new IdentityHashMap<>());
        if (!(canonical instanceof Map<?, ?> map)) {
            throw LogicalResourceContractException.invalid();
        }
        return new SchemaEnvelope(envelope.format(), envelope.version().trim(), stringMap(map));
    }

    static SchemaEnvelope copy(SchemaEnvelope envelope) {
        return new SchemaEnvelope(envelope.format(), envelope.version(), deepCopyMap(envelope.schema()));
    }

    static Object canonicalValue(Object value) {
        return canonicalValue(value, "", new IdentityHashMap<>());
    }

    private static Object canonicalValue(Object value, String parentKey,
                                         IdentityHashMap<Object, Boolean> ancestors) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof BigDecimal || value instanceof BigInteger
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) {
            return value;
        }
        if (value instanceof Float number) {
            if (!Float.isFinite(number)) {
                throw LogicalResourceContractException.invalid();
            }
            return number;
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) {
                throw LogicalResourceContractException.invalid();
            }
            return number;
        }
        if (value instanceof Map<?, ?> map) {
            enter(value, ancestors);
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String)) {
                    throw LogicalResourceContractException.invalid();
                }
                sorted.put((String) key, canonicalValue(item, (String) key, ancestors));
            });
            ancestors.remove(value);
            return new LinkedHashMap<>(sorted);
        }
        if (value instanceof List<?> list) {
            enter(value, ancestors);
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object item : list) {
                normalized.add(canonicalValue(item, "", ancestors));
            }
            ancestors.remove(value);
            if (SET_LIKE_ARRAY_KEYS.contains(parentKey)) {
                normalized = normalized.stream().distinct()
                        .sorted(Comparator.comparing(LogicalResourceContractCanonicalizer::stableLabel))
                        .toList();
            }
            return new ArrayList<>(normalized);
        }
        throw LogicalResourceContractException.invalid();
    }

    private static void enter(Object value, IdentityHashMap<Object, Boolean> ancestors) {
        if (ancestors.put(value, Boolean.TRUE) != null) {
            throw LogicalResourceContractException.invalid();
        }
    }

    private static String stableLabel(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.toString();
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringMap(Map<?, ?> source) {
        return (Map<String, Object>) source;
    }

    private static Map<String, Object> deepCopyMap(Map<String, Object> source) {
        Object copy = canonicalValue(source, "", new IdentityHashMap<>());
        return stringMap((Map<?, ?>) copy);
    }
}
