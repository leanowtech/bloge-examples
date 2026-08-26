package com.leanowtech.bloge.gateway.testing.world;

import java.util.List;
import java.util.Map;

/** Versioned state-spec boundary shared by v1 stateless and v2 stateful declarations. */
public sealed interface WorldStateSpec permits StateSpec, StateSpecV2 {
    String schemaVersion();
    boolean isEmpty();
    List<StateKeySpec> declarations();
    Map<String, Object> fingerprintMaterial();

    default String fingerprint() {
        return com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint
                .fromMaterial(fingerprintMaterial());
    }

    default void validateOverrides(Map<String, Object> overrides) {
        if (overrides == null || !overrides.isEmpty()) {
            throw new WorldModelException(WorldModelException.Code.STATE_NOT_SUPPORTED);
        }
    }
}
