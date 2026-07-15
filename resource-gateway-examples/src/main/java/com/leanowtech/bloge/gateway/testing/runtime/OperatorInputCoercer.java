package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.OperatorMetadata;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/** Converts JSON-shaped public operator input into the exact registry-declared Java input type. */
public final class OperatorInputCoercer {

    private OperatorInputCoercer() {
    }

    /**
     * Coerces an operator input using the same envelope conventions as visual DSL execution.
     *
     * @param input decoded public JSON input
     * @param metadata frozen operator metadata
     * @param objectMapper application mapper with domain modules registered
     * @return typed operator input
     * @throws IllegalArgumentException when the input cannot be represented by the declared type
     */
    public static Object coerce(Object input, OperatorMetadata metadata, ObjectMapper objectMapper) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(objectMapper, "objectMapper");
        Class<?> targetClass = metadata.inputClass();
        if (targetClass == null || Object.class.equals(targetClass)) {
            return input;
        }
        if (input == null || targetClass.isInstance(input)) {
            return input;
        }
        if (Void.class.equals(targetClass) || Void.TYPE.equals(targetClass)) {
            return null;
        }
        if (Map.class.isAssignableFrom(targetClass)) {
            return input;
        }
        Object source = input;
        if (input instanceof Map<?, ?> map && shouldUnwrapEnvelope(targetClass)) {
            source = unwrapEnvelope(map);
        }
        Type targetType = metadata.inputType() == null ? targetClass : metadata.inputType();
        try {
            return objectMapper.convertValue(source, objectMapper.constructType(targetType));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Operator input cannot be converted to "
                    + targetType.getTypeName() + ": " + failure.getMessage(), failure);
        }
    }

    private static boolean shouldUnwrapEnvelope(Class<?> targetClass) {
        return targetClass.isPrimitive()
                || targetClass.isEnum()
                || targetClass.isArray()
                || CharSequence.class.isAssignableFrom(targetClass)
                || Number.class.isAssignableFrom(targetClass)
                || Boolean.class.equals(targetClass)
                || Character.class.equals(targetClass)
                || Collection.class.isAssignableFrom(targetClass);
    }

    private static Object unwrapEnvelope(Map<?, ?> map) {
        if (map.containsKey("input")) {
            return map.get("input");
        }
        if (map.containsKey("value")) {
            return map.get("value");
        }
        return map.size() == 1 ? map.values().iterator().next() : map;
    }
}
