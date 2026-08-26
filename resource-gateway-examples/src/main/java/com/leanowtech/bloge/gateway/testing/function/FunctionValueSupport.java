package com.leanowtech.bloge.gateway.testing.function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Package-private JSON and bound policy shared by function control value objects. */
final class FunctionValueSupport {

    static final FunctionControlLimits LIMITS = FunctionControlLimits.CURRENT;
    static final int MAX_STRING_LENGTH = LIMITS.maxStringChars();
    static final int MAX_LIST_ENTRIES = LIMITS.maxListEntries();
    static final int MAX_OBJECT_ENTRIES = LIMITS.maxObjectEntries();
    static final int MAX_DEPTH = LIMITS.maxJsonValueDepth();
    static final int MAX_JSON_BYTES = LIMITS.maxJsonValueBytes();
    static final int MAX_SCHEMA_BYTES = LIMITS.maxSchemaBytes();
    static final int MAX_SCHEMA_DEPTH = LIMITS.maxSchemaDepth();
    static final long MAX_CONSUMPTION = LIMITS.maxConsumption();
    static final long MAX_DURATION_MILLIS = LIMITS.maxDurationMillis();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FunctionValueSupport() {
    }

    static String text(String value, boolean required, FunctionControlException.Code code) {
        String raw = value == null ? "" : value;
        for (int i = 0; i < raw.length(); i++) {
            if (Character.isISOControl(raw.charAt(i))) {
                throw new FunctionControlException(code);
            }
        }
        String normalized = raw.trim();
        if (required && normalized.isEmpty() || normalized.length() > MAX_STRING_LENGTH) {
            throw new FunctionControlException(code);
        }
        return normalized;
    }

    static Object freeze(Object value) {
        try {
            Object frozen = ProtocolJsonValue.freeze(value);
            validateBounds(frozen, MAX_DEPTH, MAX_JSON_BYTES);
            return frozen;
        } catch (FunctionControlException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new FunctionControlException(FunctionControlException.Code.VALUE_INVALID, failure);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> schema(Object value) {
        if (value == null) {
            return Map.of();
        }
        Object frozen = freeze(value);
        if (!(frozen instanceof Map<?, ?> raw)) {
            throw new FunctionControlException(FunctionControlException.Code.SCHEMA_INVALID);
        }
        if (jsonDepth(frozen) > MAX_SCHEMA_DEPTH || jsonBytes(frozen) > MAX_SCHEMA_BYTES) {
            throw new FunctionControlException(FunctionControlException.Code.LIMIT_EXCEEDED);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new FunctionControlException(FunctionControlException.Code.SCHEMA_INVALID);
            }
            result.put(key, entry.getValue());
        }
        if (result.isEmpty()) {
            return Map.of();
        }
        try {
            if (!VisualSchemaValidator.validateSchema(
                    new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", result).schema(),
                    "/functionSchema").isEmpty()) {
                throw new FunctionControlException(FunctionControlException.Code.SCHEMA_INVALID);
            }
        } catch (FunctionControlException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new FunctionControlException(FunctionControlException.Code.SCHEMA_INVALID, failure);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    static boolean accepts(Map<String, Object> schema, Object value) {
        if (schema == null || schema.isEmpty()) {
            return true;
        }
        try {
            return VisualSchemaValidator.validateValue(
                    new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema), value,
                    "/functionValue").isEmpty();
        } catch (RuntimeException failure) {
            throw new FunctionControlException(FunctionControlException.Code.SCHEMA_INVALID, failure);
        }
    }

    static List<Object> arguments(List<?> arguments) {
        if (arguments == null) {
            return null;
        }
        if (arguments.size() > MAX_LIST_ENTRIES) {
            throw new FunctionControlException(FunctionControlException.Code.LIMIT_EXCEEDED);
        }
        Object frozen = freeze(arguments);
        if (!(frozen instanceof List<?> list)) {
            throw new FunctionControlException(FunctionControlException.Code.VALUE_INVALID);
        }
        return List.copyOf(list);
    }

    static String fingerprint(Object value) {
        try {
            return ProtocolFingerprint.ofBounded(MAPPER, value, MAX_JSON_BYTES);
        } catch (RuntimeException failure) {
            throw new FunctionControlException(FunctionControlException.Code.VALUE_INVALID, failure);
        }
    }

    static int jsonDepth(Object value) {
        return jsonDepth(value, 0, new IdentityHashMap<>());
    }

    private static int jsonDepth(Object value, int depth, IdentityHashMap<Object, Boolean> seen) {
        if (!(value instanceof Map<?, ?> || value instanceof Collection<?>)) {
            return depth;
        }
        if (seen.put(value, Boolean.TRUE) != null) {
            throw new FunctionControlException(FunctionControlException.Code.VALUE_INVALID);
        }
        try {
            int result = depth;
            if (value instanceof Map<?, ?> map) {
                if (map.size() > MAX_OBJECT_ENTRIES) {
                    throw new FunctionControlException(FunctionControlException.Code.LIMIT_EXCEEDED);
                }
                for (Object nested : map.values()) {
                    result = Math.max(result, jsonDepth(nested, depth + 1, seen));
                }
            } else {
                Collection<?> collection = (Collection<?>) value;
                if (collection.size() > MAX_LIST_ENTRIES) {
                    throw new FunctionControlException(FunctionControlException.Code.LIMIT_EXCEEDED);
                }
                for (Object nested : collection) {
                    result = Math.max(result, jsonDepth(nested, depth + 1, seen));
                }
            }
            return result;
        } finally {
            seen.remove(value);
        }
    }

    private static void validateBounds(Object value, int maxDepth, int maxBytes) {
        if (jsonDepth(value) > maxDepth || jsonBytes(value) > maxBytes) {
            throw new FunctionControlException(FunctionControlException.Code.LIMIT_EXCEEDED);
        }
    }

    private static int jsonBytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value).length;
        } catch (Exception failure) {
            throw new FunctionControlException(FunctionControlException.Code.VALUE_INVALID, failure);
        }
    }
}
