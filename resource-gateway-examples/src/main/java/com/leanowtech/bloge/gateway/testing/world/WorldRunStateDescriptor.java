package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Map;

/** Server-owned run-state material retained beside a compilation, never reverse-parsed from metadata. */
public record WorldRunStateDescriptor(
        WorldStateSpec stateSpec,
        Map<String, Object> initialOverrides,
        String scenarioFingerprint,
        String worldFingerprint,
        String graphArtifactFingerprint
) {
    private static final int MAX_OVERRIDE_BYTES = 256 * 1024;

    public WorldRunStateDescriptor {
        if (stateSpec == null || initialOverrides == null || scenarioFingerprint == null
                || worldFingerprint == null || graphArtifactFingerprint == null
                || !validFingerprint(scenarioFingerprint)
                || !validFingerprint(worldFingerprint)
                || !validFingerprint(graphArtifactFingerprint)) {
            throw new WorldModelException(WorldModelException.Code.STATE_INPUT_INVALID);
        }
        try {
            stateSpec.validateOverrides(initialOverrides);
            @SuppressWarnings("unchecked") Map<String, Object> frozen =
                    (Map<String, Object>) (Map<?, ?>) ProtocolJsonValue.freeze(initialOverrides);
            ProtocolFingerprint.ofBounded(WorldStateSession.MAPPER, frozen, MAX_OVERRIDE_BYTES);
            initialOverrides = frozen;
        } catch (WorldModelException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw new WorldModelException(WorldModelException.Code.STATE_INPUT_INVALID);
        }
        scenarioFingerprint = scenarioFingerprint.trim();
        worldFingerprint = worldFingerprint.trim();
        graphArtifactFingerprint = graphArtifactFingerprint.trim();
    }

    public boolean stateful() {
        return !stateSpec.isEmpty();
    }

    /** The override values remain server-owned; only this semantic identity enters compilation fingerprints. */
    public String fingerprint() {
        try {
            String stateFingerprint = stateSpec.fingerprint();
            return ProtocolFingerprint.of(WorldStateSession.MAPPER, Map.of(
                    "stateSpecFingerprint", stateFingerprint,
                    "initialOverrides", initialOverrides,
                    "scenarioFingerprint", scenarioFingerprint,
                    "worldFingerprint", worldFingerprint,
                    "graphArtifactFingerprint", graphArtifactFingerprint));
        } catch (RuntimeException invalid) {
            throw new WorldModelException(WorldModelException.Code.STATE_INPUT_INVALID);
        }
    }

    static WorldRunStateDescriptor legacy() {
        return new WorldRunStateDescriptor(StateSpec.empty(), Map.of(),
                "sha256:" + "0".repeat(64),
                "sha256:" + "0".repeat(64),
                "sha256:" + "0".repeat(64));
    }

    private static boolean validFingerprint(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }
}
