package com.leanowtech.bloge.gateway.testing.world;

import java.util.List;
import java.util.Map;

/** Stage 1 state declaration. The only v1 value is the stateless declaration. */
public final class StateSpec implements WorldStateSpec {
    private static final StateSpec EMPTY = new StateSpec();

    private StateSpec() {
    }

    public static StateSpec empty() {
        return EMPTY;
    }

    public static StateSpec of(Map<String, ?> ignored) {
        if (ignored != null && ignored.isEmpty()) return EMPTY;
        throw new WorldModelException(WorldModelException.Code.STATE_NOT_SUPPORTED);
    }

    public static StateSpec of(java.util.Set<String> keys, Map<String, ?> defaults) {
        if (keys != null && defaults != null && keys.isEmpty() && defaults.isEmpty()) return EMPTY;
        throw new WorldModelException(WorldModelException.Code.STATE_NOT_SUPPORTED);
    }

    @Override public String schemaVersion() { return "bloge.worldStateSpec.v1"; }
    @Override public boolean isEmpty() { return true; }
    /** Source-compatible Stage 1 API. */
    public java.util.Set<String> keys() { return java.util.Set.of(); }
    /** Source-compatible Stage 1 API. */
    public Map<String, Object> defaults() { return Map.of(); }
    @Override public List<StateKeySpec> declarations() { return List.of(); }
    @Override public Map<String, Object> fingerprintMaterial() {
        return Map.of("schemaVersion", schemaVersion(), "keys", List.of());
    }
}
