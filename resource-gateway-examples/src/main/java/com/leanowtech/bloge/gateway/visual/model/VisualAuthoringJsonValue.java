package com.leanowtech.bloge.gateway.visual.model;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Defensively freezes JSON-shaped values carried by mutable visual authoring assets.
 *
 * <p>Scenario inputs and examples are intentionally payload-bearing authoring data. Keeping this
 * helper in the visual model package prevents records from exposing caller-owned nested maps or
 * lists while rejecting cyclic values that cannot be represented by the wire protocol.</p>
 */
public final class VisualAuthoringJsonValue {

    private VisualAuthoringJsonValue() {
    }

    /**
     * Returns a deeply immutable JSON-shaped value.
     *
     * @param value scalar, map, collection, or array value
     * @return deeply immutable value
     * @throws IllegalArgumentException when a cyclic object graph is supplied
     */
    public static Object freeze(Object value) {
        return freeze(value, new IdentityHashMap<>());
    }

    /**
     * Returns a deeply immutable string-keyed map.
     *
     * @param value source map
     * @return deeply immutable map
     * @throws IllegalArgumentException when a cyclic object graph is supplied
     */
    public static Map<String, Object> freezeMap(Map<String, ?> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        Object frozen = freeze(value);
        if (!(frozen instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Visual authoring JSON map could not be frozen");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return Collections.unmodifiableMap(result);
    }

    private static Object freeze(Object value, IdentityHashMap<Object, Boolean> visiting) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Enum<?>) {
            return value;
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Visual authoring JSON values must not contain cycles");
        }
        try {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> frozen = new LinkedHashMap<>();
                map.forEach((key, item) -> {
                    if (key == null) {
                        throw new IllegalArgumentException("Visual authoring JSON map keys must not be null");
                    }
                    frozen.put(String.valueOf(key), freeze(item, visiting));
                });
                return Collections.unmodifiableMap(frozen);
            }
            if (value instanceof Iterable<?> iterable) {
                List<Object> frozen = new ArrayList<>();
                iterable.forEach(item -> frozen.add(freeze(item, visiting)));
                return List.copyOf(frozen);
            }
            if (value.getClass().isArray()) {
                List<Object> frozen = new ArrayList<>(Array.getLength(value));
                for (int index = 0; index < Array.getLength(value); index++) {
                    frozen.add(freeze(Array.get(value, index), visiting));
                }
                return List.copyOf(frozen);
            }
            return value;
        } finally {
            visiting.remove(value);
        }
    }
}
