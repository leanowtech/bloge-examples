package com.leanowtech.bloge.gateway.testing.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/** One normalized state key declaration; writer ownership belongs to its slice coordinate. */
public record StateKeySpec(String key, Access access, Map<String, Object> schema, Object defaultValue) {
    public enum Access { READ, WRITE, READ_WRITE }
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public StateKeySpec {
        if (key == null || key.isBlank() || !key.equals(key.trim()) || key.length() > 256
                || !key.startsWith("/") || key.contains("//") || access == null || schema == null) throw invalid();
        key = key.trim();
        schema = immutableSchema(schema);
        if (depth(schema, 0) > StateSpecV2.MAX_DEPTH) throw invalid();
        defaultValue = freeze(defaultValue);
        if (depth(defaultValue, 0) > StateSpecV2.MAX_DEPTH) throw invalid();
        boundedBytes(schema, StateSpecV2.MAX_SCHEMA_BYTES);
        boundedBytes(defaultValue, StateSpecV2.MAX_DEFAULT_BYTES);
        SchemaEnvelope envelope = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema);
        if (!VisualSchemaValidator.validateSchema(envelope.schema(), "/schema").isEmpty()
                || !VisualSchemaValidator.validateValue(envelope, defaultValue, "/default").isEmpty()) {
            throw invalid();
        }
    }

    public boolean writes() { return access == Access.WRITE || access == Access.READ_WRITE; }

    public Map<String, Object> fingerprintMaterial() {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("key", key);
        material.put("access", access.name());
        material.put("schema", schema);
        material.put("defaultValue", defaultValue);
        return Collections.unmodifiableMap(material);
    }

    public boolean accepts(Object value) {
        return VisualSchemaValidator.validateValue(
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema), value, "/value").isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> immutableSchema(Map<String, Object> value) {
        Object frozen = freeze(value);
        if (!(frozen instanceof Map<?, ?> map) || map.size() > 128) throw invalid();
        for (Object key : map.keySet()) if (!(key instanceof String)) throw invalid();
        return (Map<String, Object>) (Map<?, ?>) frozen;
    }

    private static Object freeze(Object value) {
        if (value == null) return null;
        try { return ProtocolJsonValue.freeze(value); } catch (RuntimeException invalid) { throw invalid(); }
    }

    private static int depth(Object value, int current) {
        if (value instanceof Map<?, ?> map) {
            int deepest = current;
            for (Object nested : map.values()) deepest = Math.max(deepest, depth(nested, current + 1));
            return deepest;
        }
        if (value instanceof Iterable<?> values) {
            int deepest = current;
            for (Object nested : values) deepest = Math.max(deepest, depth(nested, current + 1));
            return deepest;
        }
        return current;
    }

    private static void boundedBytes(Object value, int maximum) {
        try {
            int bytes = MAPPER.writeValueAsBytes(value).length;
            if (bytes > maximum) throw invalid();
        } catch (RuntimeException invalid) {
            throw invalid();
        } catch (Exception invalid) {
            throw invalid();
        }
    }
    private static WorldModelException invalid() { return new WorldModelException(WorldModelException.Code.STATE_NOT_SUPPORTED); }
}
