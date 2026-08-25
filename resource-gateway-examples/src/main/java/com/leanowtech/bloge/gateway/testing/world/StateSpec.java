package com.leanowtech.bloge.gateway.testing.world;

import java.util.Map;
import java.util.Set;

/**
 * State declaration for a world slice.
 *
 * <p>Stage 1 is deliberately stateless. There is exactly one representable value. A future
 * stateful implementation must be introduced as a versioned Stage 2 type instead of extending
 * this type with a map that can accidentally become executable.</p>
 */
public final class StateSpec {
    private static final StateSpec EMPTY = new StateSpec();

    private StateSpec() {
    }

    public static StateSpec empty() {
        return EMPTY;
    }

    /** Any state key or default is rejected until Stage 2. */
    public static StateSpec of(Map<String, ?> keysAndDefaults) {
        if (keysAndDefaults != null && keysAndDefaults.isEmpty()) {
            return EMPTY;
        }
        throw new WorldModelException(WorldModelException.Code.STATE_NOT_SUPPORTED);
    }

    /** Any declared key or default is rejected until Stage 2. */
    public static StateSpec of(Set<String> keys, Map<String, ?> defaults) {
        if (keys != null && defaults != null && keys.isEmpty() && defaults.isEmpty()) {
            return EMPTY;
        }
        throw new WorldModelException(WorldModelException.Code.STATE_NOT_SUPPORTED);
    }

    public boolean isEmpty() {
        return true;
    }

    public Set<String> keys() {
        return Set.of();
    }

    public Map<String, Object> defaults() {
        return Map.of();
    }
}
