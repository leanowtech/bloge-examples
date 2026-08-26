package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned state declaration with access, JSON Schema and canonical defaults per key. */
public record StateSpecV2(String schemaVersion, List<StateKeySpec> keys) implements WorldStateSpec {
    public static final String SCHEMA_VERSION = "bloge.worldStateSpec.v2";
    public static final int MAX_KEYS = 128;
    public static final int MAX_SCHEMA_BYTES = 32 * 1024;
    public static final int MAX_DEFAULT_BYTES = 64 * 1024;
    public static final int MAX_DEPTH = 16;

    public StateSpecV2 {
        if (!SCHEMA_VERSION.equals(schemaVersion) || keys == null || keys.isEmpty() || keys.size() > MAX_KEYS
                || keys.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        List<StateKeySpec> ordered = new ArrayList<>(keys);
        ordered.sort(Comparator.comparing(StateKeySpec::key));
        Set<String> names = new LinkedHashSet<>();
        for (StateKeySpec key : ordered) if (!names.add(key.key())) throw invalid();
        for (int index = 0; index < ordered.size(); index++) {
            for (int nested = index + 1; nested < ordered.size(); nested++) {
                if (StatePointer.isPrefix(ordered.get(index).key(), ordered.get(nested).key())) {
                    throw invalid();
                }
            }
        }
        keys = List.copyOf(ordered);
    }

    public static StateSpecV2 of(List<StateKeySpec> keys) { return new StateSpecV2(SCHEMA_VERSION, keys); }
    @Override public boolean isEmpty() { return keys.isEmpty(); }
    @Override public List<StateKeySpec> declarations() { return keys; }
    @Override public Map<String, Object> fingerprintMaterial() {
        return Map.of("schemaVersion", schemaVersion, "keys", keys.stream().map(StateKeySpec::fingerprintMaterial).toList());
    }
    public String fingerprint() { return VisualBundleFingerprint.fromMaterial(fingerprintMaterial()); }

    @Override public void validateOverrides(Map<String, Object> overrides) {
        if (overrides == null || overrides.size() > MAX_KEYS) throw invalid();
        Map<String, StateKeySpec> declarations = keys.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(StateKeySpec::key, value -> value));
        for (Map.Entry<String, Object> override : overrides.entrySet()) {
            StateKeySpec declaration = declarations.get(override.getKey());
            if (declaration == null || !declaration.accepts(override.getValue())) throw invalid();
        }
    }

    static WorldModelException invalid() { return new WorldModelException(WorldModelException.Code.STATE_NOT_SUPPORTED); }
}
