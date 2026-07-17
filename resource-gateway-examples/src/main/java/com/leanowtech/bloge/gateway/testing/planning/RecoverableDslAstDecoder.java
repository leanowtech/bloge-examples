package com.leanowtech.bloge.gateway.testing.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.dsl.ast.AstNode.GraphDef;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fail-closed decoder for BLOGE's tagged recoverable DSL AST payload.
 *
 * <p>The BLOGE zero-dependency codec preserves record and enum class tags, but its generic
 * collection coercion does not currently restore every {@code Set<T>} nested inside an AST record.
 * This decoder closes that gap without accepting arbitrary tagged Java classes. Only DSL AST
 * records and the small set of DSL/core enums referenced by those records may be instantiated.</p>
 */
final class RecoverableDslAstDecoder {
    private static final int MAX_SOURCE_BYTES = 1_048_576;
    private static final int MAX_DEPTH = 128;
    private static final String DSL_AST_CLASS_PREFIX = "com.leanowtech.bloge.dsl.ast.";
    private static final Set<String> ALLOWED_EXACT_CLASSES = Set.of(
            "com.leanowtech.bloge.core.model.UpstreamResolutionPolicy",
            "com.leanowtech.bloge.core.engine.operators.ItemFailurePolicy");

    private final ObjectMapper objectMapper;

    /** @param objectMapper bounded JSON tree decoder */
    RecoverableDslAstDecoder(ObjectMapper objectMapper) {
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Restores one exact graph definition and rejects unknown or partially reconstructed types.
     *
     * @param payload tagged JSON emitted by BLOGE's recoverable graph definition codec
     * @return fully typed graph AST
     */
    GraphDef decode(String payload) {
        if (payload == null || payload.isBlank() || payload.length() > MAX_SOURCE_BYTES
                || payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("Recoverable DSL AST payload is absent or unbounded");
        }
        try {
            Object raw = objectMapper.readValue(payload, Object.class);
            Object decoded = decodeValue(raw, 0);
            if (!(decoded instanceof GraphDef graph)) {
                throw new IllegalArgumentException("Recoverable DSL payload is not a GraphDef");
            }
            requireTypedMembers(graph.members(), 0);
            return graph;
        } catch (JsonProcessingException malformed) {
            throw new IllegalArgumentException("Recoverable DSL AST payload is malformed", malformed);
        }
    }

    private Object decodeValue(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Recoverable DSL AST exceeds maximum depth");
        }
        if (value instanceof List<?> list) {
            List<Object> decoded = new ArrayList<>(list.size());
            for (Object item : list) {
                decoded.add(decodeValue(item, depth + 1));
            }
            return decoded;
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            return value;
        }
        Map<String, Object> map = stringMap(rawMap);
        if (map.size() == 2 && map.containsKey("__enumClass__")
                && map.containsKey("__value__")) {
            return enumValue(map);
        }
        if (map.size() == 2 && map.containsKey("__recordClass__")
                && map.containsKey("__data__")) {
            return recordValue(map, depth);
        }
        Map<String, Object> decoded = new LinkedHashMap<>();
        map.forEach((key, item) -> decoded.put(key, decodeValue(item, depth + 1)));
        return decoded;
    }

    private Object recordValue(Map<String, Object> wrapper, int depth) {
        String className = requiredString(wrapper.get("__recordClass__"), "record class");
        Class<?> type = allowedClass(className);
        if (!type.isRecord()) {
            throw new IllegalArgumentException("Tagged recoverable DSL type is not a record");
        }
        if (!(wrapper.get("__data__") instanceof Map<?, ?> rawData)) {
            throw new IllegalArgumentException("Tagged recoverable DSL record has no data object");
        }
        Map<String, Object> data = stringMap(rawData);
        RecordComponent[] components = type.getRecordComponents();
        Set<String> expectedComponents = java.util.Arrays.stream(components)
                .map(RecordComponent::getName).collect(java.util.stream.Collectors.toSet());
        if (!data.keySet().equals(expectedComponents)) {
            throw new IllegalArgumentException(
                    "Tagged recoverable DSL record fields are inconsistent");
        }
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        try {
            for (int index = 0; index < components.length; index++) {
                RecordComponent component = components[index];
                if (!data.containsKey(component.getName())) {
                    throw new IllegalArgumentException(
                            "Tagged recoverable DSL record is missing " + component.getName());
                }
                parameterTypes[index] = component.getType();
                arguments[index] = coerce(decodeValue(data.get(component.getName()), depth + 1),
                        component.getGenericType(), depth + 1);
            }
            Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor.newInstance(arguments);
        } catch (ReflectiveOperationException | ClassCastException failure) {
            throw new IllegalArgumentException(
                    "Tagged recoverable DSL record could not be reconstructed", failure);
        }
    }

    private Object enumValue(Map<String, Object> wrapper) {
        String className = requiredString(wrapper.get("__enumClass__"), "enum class");
        String value = requiredString(wrapper.get("__value__"), "enum value");
        Class<?> type = allowedClass(className);
        if (!type.isEnum()) {
            throw new IllegalArgumentException("Tagged recoverable DSL type is not an enum");
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object result = Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), value);
        return result;
    }

    private Object coerce(Object value, Type target, int depth) {
        if (value == null) {
            return null;
        }
        if (target instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            if (raw instanceof Class<?> collectionType
                    && Collection.class.isAssignableFrom(collectionType)
                    && value instanceof Collection<?> values) {
                Type elementType = parameterized.getActualTypeArguments()[0];
                Collection<Object> result = Set.class.isAssignableFrom(collectionType)
                        ? new LinkedHashSet<>() : new ArrayList<>();
                for (Object item : values) {
                    result.add(coerce(item, elementType, depth + 1));
                }
                return Set.class.isAssignableFrom(collectionType)
                        ? Set.copyOf(result) : List.copyOf(result);
            }
            if (raw instanceof Class<?> mapType && Map.class.isAssignableFrom(mapType)
                    && value instanceof Map<?, ?> values) {
                Type keyType = parameterized.getActualTypeArguments()[0];
                Type valueType = parameterized.getActualTypeArguments()[1];
                Map<Object, Object> result = new LinkedHashMap<>();
                values.forEach((key, item) -> result.put(
                        coerce(key, keyType, depth + 1),
                        coerce(item, valueType, depth + 1)));
                return result;
            }
            if (raw instanceof Class<?> rawClass) {
                return coerceClass(value, rawClass, depth);
            }
        }
        if (target instanceof GenericArrayType genericArray && value instanceof List<?> values) {
            Type component = genericArray.getGenericComponentType();
            Class<?> componentClass = component instanceof Class<?> type ? type : Object.class;
            Object result = Array.newInstance(componentClass, values.size());
            for (int index = 0; index < values.size(); index++) {
                Array.set(result, index, coerce(values.get(index), component, depth + 1));
            }
            return result;
        }
        if (target instanceof Class<?> type) {
            return coerceClass(value, type, depth);
        }
        return value;
    }

    private Object coerceClass(Object value, Class<?> target, int depth) {
        if (target.isInstance(value)) {
            return value;
        }
        if (target == boolean.class && value instanceof Boolean) {
            return value;
        }
        if (target == char.class && value instanceof Character) {
            return value;
        }
        if ((target == int.class || target == Integer.class) && value instanceof Number number) {
            return number.intValue();
        }
        if ((target == long.class || target == Long.class) && value instanceof Number number) {
            return number.longValue();
        }
        if ((target == double.class || target == Double.class) && value instanceof Number number) {
            return number.doubleValue();
        }
        if ((target == float.class || target == Float.class) && value instanceof Number number) {
            return number.floatValue();
        }
        if ((target == short.class || target == Short.class) && value instanceof Number number) {
            return number.shortValue();
        }
        if ((target == byte.class || target == Byte.class) && value instanceof Number number) {
            return number.byteValue();
        }
        if (target.isArray() && value instanceof List<?> values) {
            Object result = Array.newInstance(target.getComponentType(), values.size());
            for (int index = 0; index < values.size(); index++) {
                Array.set(result, index,
                        coerce(values.get(index), target.getComponentType(), depth + 1));
            }
            return result;
        }
        throw new IllegalArgumentException("Recoverable DSL value type "
                + value.getClass().getName() + " is incompatible with " + target.getName());
    }

    private static void requireTypedMembers(List<?> members, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Recoverable DSL members exceed maximum depth");
        }
        for (Object member : members) {
            if (!(member instanceof com.leanowtech.bloge.dsl.ast.AstNode node)) {
                throw new IllegalArgumentException("Recoverable DSL contains an untyped member");
            }
            switch (node) {
                case com.leanowtech.bloge.dsl.ast.AstNode.ForEachDef nested ->
                        requireTypedMembers(nested.body(), depth + 1);
                case com.leanowtech.bloge.dsl.ast.AstNode.LoopDef nested ->
                        requireTypedMembers(nested.body(), depth + 1);
                case com.leanowtech.bloge.dsl.ast.AstNode.ExtensionDef extension ->
                        requireTypedMembers(extension.children(), depth + 1);
                default -> {
                    // Other generation-one AST members do not own generic AstNode collections.
                }
            }
        }
    }

    private static Class<?> allowedClass(String className) {
        if (!className.startsWith(DSL_AST_CLASS_PREFIX)
                && !ALLOWED_EXACT_CLASSES.contains(className)) {
            throw new IllegalArgumentException("Tagged recoverable DSL class is not allowed");
        }
        try {
            return Class.forName(className, false, RecoverableDslAstDecoder.class.getClassLoader());
        } catch (ClassNotFoundException missing) {
            throw new IllegalArgumentException("Tagged recoverable DSL class is unavailable", missing);
        }
    }

    private static Map<String, Object> stringMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException("Recoverable DSL JSON object key is not text");
            }
            result.put(text, item);
        });
        return result;
    }

    private static String requiredString(Object value, String name) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 512) {
            throw new IllegalArgumentException("Recoverable DSL " + name + " is invalid");
        }
        return text;
    }
}
