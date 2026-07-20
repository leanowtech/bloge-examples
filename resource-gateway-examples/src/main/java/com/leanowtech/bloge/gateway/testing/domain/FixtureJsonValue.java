package com.leanowtech.bloge.gateway.testing.domain;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Recursively detaches and freezes JSON containers embedded in fixture protocol values. */
final class FixtureJsonValue {

    private static final int MAX_DEPTH = 128;

    private FixtureJsonValue() {
    }

    /** Returns a recursively copied, unmodifiable JSON value. */
    static Object freeze(Object value) {
        return freeze(value, new IdentityHashMap<>(), 0);
    }

    /** Returns a recursively copied, unmodifiable JSON object. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> freezeMap(Map<String, ?> value) {
        return value == null ? Map.of() : (Map<String, Object>) freeze(value);
    }

    private static Object freeze(Object value, IdentityHashMap<Object, Boolean> path, int depth) {
        if (value == null || !container(value)) {
            return value;
        }
        if (depth >= MAX_DEPTH) {
            throw new IllegalArgumentException("Fixture JSON value exceeds maximum nesting depth");
        }
        if (path.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Fixture JSON value contains a cycle");
        }
        try {
            if (value instanceof Map<?, ?> source) {
                Map<String, Object> copy = new LinkedHashMap<>();
                source.forEach((key, nested) -> {
                    if (!(key instanceof String text)) {
                        throw new IllegalArgumentException("Fixture JSON object key must be a string");
                    }
                    copy.put(text, freeze(nested, path, depth + 1));
                });
                return Collections.unmodifiableMap(copy);
            }
            List<Object> copy = new ArrayList<>();
            if (value instanceof Collection<?> source) {
                source.forEach(nested -> copy.add(freeze(nested, path, depth + 1)));
            } else {
                int length = Array.getLength(value);
                for (int index = 0; index < length; index++) {
                    copy.add(freeze(Array.get(value, index), path, depth + 1));
                }
            }
            return Collections.unmodifiableList(copy);
        } finally {
            path.remove(value);
        }
    }

    private static boolean container(Object value) {
        return value instanceof Map<?, ?> || value instanceof Collection<?>
                || value.getClass().isArray();
    }
}
