package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.List;
import java.util.Map;
import java.util.Comparator;

/** Strictly bound, immutable state checkpoint. State is retained only for controlled restore. */
public record WorldStateSnapshot(
        WorldStateSession.Binding binding,
        String stateSpecFingerprint,
        long revision,
        Map<String, Object> state,
        List<WorldStateTransactionObservation> observations,
        String fingerprint
) {
    private static final String FINGERPRINT = "sha256:[0-9a-f]{64}";

    public WorldStateSnapshot {
        if (binding == null || stateSpecFingerprint == null
                || !stateSpecFingerprint.matches(FINGERPRINT)
                || revision < 0 || state == null || observations == null
                || observations.stream().anyMatch(java.util.Objects::isNull)
                || fingerprint == null || fingerprint.isBlank()) {
            throw new WorldModelException(WorldModelException.Code.STATE_SNAPSHOT_INVALID);
        }
        try {
            state = freeze(state);
            observations = canonicalObservations(observations);
            String expected = fingerprint(binding, stateSpecFingerprint, revision, state, observations);
            if (!expected.equals(fingerprint)) {
                throw new WorldModelException(WorldModelException.Code.STATE_SNAPSHOT_TAMPERED);
            }
        } catch (WorldModelException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw new WorldModelException(WorldModelException.Code.STATE_SNAPSHOT_INVALID);
        }
    }

    public static String fingerprint(WorldStateSession.Binding binding,
                                     String stateSpecFingerprint,
                                     long revision,
                                     Map<String, Object> state,
                                     List<WorldStateTransactionObservation> observations) {
        try {
            if (binding == null || stateSpecFingerprint == null
                    || !stateSpecFingerprint.matches(FINGERPRINT)
                    || revision < 0 || state == null || observations == null
                    || observations.stream().anyMatch(java.util.Objects::isNull)) {
                throw new WorldModelException(WorldModelException.Code.STATE_SNAPSHOT_INVALID);
            }
            return ProtocolFingerprint.of(WorldStateSession.MAPPER, Map.of(
                    "binding", binding,
                    "stateSpecFingerprint", stateSpecFingerprint,
                    "revision", revision,
                    "state", state,
                    "observations", canonicalObservations(observations)));
        } catch (WorldModelException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw new WorldModelException(WorldModelException.Code.STATE_SNAPSHOT_TAMPERED);
        }
    }

    static List<WorldStateTransactionObservation> canonicalObservations(
            List<WorldStateTransactionObservation> observations) {
        if (observations == null || observations.stream().anyMatch(java.util.Objects::isNull)) {
            throw new WorldModelException(WorldModelException.Code.STATE_SNAPSHOT_INVALID);
        }
        return observations.stream()
                .sorted(Comparator.comparing(value -> value.coordinate().canonicalKey()))
                .toList();
    }

    private static Map<String, Object> freeze(Map<String, ?> source) {
        try {
            @SuppressWarnings("unchecked") Map<String, Object> result =
                    (Map<String, Object>) (Map<?, ?>) ProtocolJsonValue.freeze(source);
            return result;
        } catch (RuntimeException invalid) {
            throw new WorldModelException(WorldModelException.Code.STATE_SNAPSHOT_INVALID);
        }
    }
}
