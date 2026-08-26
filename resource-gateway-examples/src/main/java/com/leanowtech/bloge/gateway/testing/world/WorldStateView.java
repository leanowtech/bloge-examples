package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Immutable, payload-detached view of the state keys admitted to one invocation. */
public record WorldStateView(Map<String, Object> values, long revision, String fingerprint) {
    public WorldStateView {
        values = freeze(values);
        if (revision < 0 || fingerprint == null
                || !fingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw new WorldModelException(WorldModelException.Code.STATE_INPUT_INVALID);
        }
    }

    public Object value(String key) {
        return values.get(key);
    }

    public boolean contains(String key) {
        return values.containsKey(key);
    }

    public Set<String> keys() {
        return values.keySet();
    }

    private static Map<String, Object> freeze(Map<String, ?> source) {
        if (source == null) {
            throw new WorldModelException(WorldModelException.Code.STATE_INPUT_INVALID);
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new WorldModelException(WorldModelException.Code.STATE_INPUT_INVALID);
            }
            try {
                copy.put(StatePointer.normalize(key), ProtocolJsonValue.freeze(value));
            } catch (RuntimeException invalid) {
                throw new WorldModelException(WorldModelException.Code.STATE_INPUT_INVALID);
            }
        });
        try {
            @SuppressWarnings("unchecked") Map<String, Object> frozen =
                    (Map<String, Object>) (Map<?, ?>) ProtocolJsonValue.freeze(copy);
            return frozen;
        } catch (RuntimeException invalid) {
            throw new WorldModelException(WorldModelException.Code.STATE_INPUT_INVALID);
        }
    }
}
