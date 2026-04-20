package com.leanowtech.bloge.graphengine.mybatis.store;

import com.leanowtech.bloge.core.checkpoint.CheckpointCodec;
import com.leanowtech.bloge.core.schema.SchemaDescriptor;
import com.leanowtech.bloge.core.schema.SchemaDescriptorJsonCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared JSON helpers for graph-engine MyBatis stores.
 */
final class GraphEngineJsonSupport {
    private GraphEngineJsonSupport() {
    }

    static String encode(CheckpointCodec checkpointCodec, Object value) {
        if (value == null) {
            return null;
        }
        return checkpointCodec.serialize(value);
    }

    static Map<String, Object> decodeMap(CheckpointCodec checkpointCodec, String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Object decoded = checkpointCodec.deserialize(json);
        if (!(decoded instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Expected JSON object payload");
        }
        return castMap(raw);
    }

    static Map<String, String> stringMap(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        LinkedHashMap<String, String> decoded = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            decoded.put(String.valueOf(entry.getKey()), entry.getValue() == null ? null : String.valueOf(entry.getValue()));
        }
        return Map.copyOf(decoded);
    }

    static Map<String, Object> objectMap(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        LinkedHashMap<String, Object> decoded = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            decoded.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Map.copyOf(decoded);
    }

    static List<String> stringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected JSON array");
        }
        ArrayList<String> decoded = new ArrayList<>(list.size());
        for (Object item : list) {
            decoded.add(String.valueOf(item));
        }
        return List.copyOf(decoded);
    }

    static Set<String> stringSet(Object raw) {
        if (raw == null) {
            return Set.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected JSON array");
        }
        LinkedHashSet<String> decoded = new LinkedHashSet<>();
        for (Object item : list) {
            decoded.add(String.valueOf(item));
        }
        return Set.copyOf(decoded);
    }

    static String stringValue(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    static Integer integer(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(raw));
    }

    static boolean booleanValue(Object raw, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean flag) {
            return flag;
        }
        if (raw instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(raw));
    }

    static Object encodeSchema(SchemaDescriptor schema) {
        return schema == null ? null : checkpointCompatibleMap(schema);
    }

    static SchemaDescriptor decodeSchema(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected schema object");
        }
        return SchemaDescriptorJsonCodec.fromMap(castMap(map));
    }

    private static Map<String, Object> checkpointCompatibleMap(SchemaDescriptor schema) {
        return schema == null ? Map.of() : schema.toMap();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }
}
