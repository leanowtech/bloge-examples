package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Encrypted data-plane aggregate for one stateful mirror session.
 *
 * <p>This value contains customer-shaped business state and must never be persisted in the
 * Resource Gateway control database, emitted to logs, or returned by descriptor APIs. A data-plane
 * store encrypts the complete canonical representation and authenticates it against the exact
 * enterprise scope, session identity, and state revision.</p>
 *
 * @param schemaVersion encrypted aggregate wire version
 * @param stateModel exact virtual-world model
 * @param writeEffects exact admitted mutation definitions
 * @param state current immutable session state
 * @param fingerprint canonical aggregate fingerprint
 */
public record MirrorSessionPayload(
        String schemaVersion,
        StateModel stateModel,
        List<WriteEffectSpec> writeEffects,
        SessionStateSpace state,
        String fingerprint
) {
    /** Current encrypted aggregate version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorSessionPayload.v1";

    /** Freezes deterministic ordering and validates complete aggregate presence. */
    public MirrorSessionPayload {
        schemaVersion = version(schemaVersion);
        stateModel = Objects.requireNonNull(stateModel, "stateModel");
        writeEffects = writeEffects == null ? List.of() : writeEffects.stream()
                .map(effect -> Objects.requireNonNull(effect, "writeEffect"))
                .sorted(Comparator.comparing(WriteEffectSpec::specId)
                        .thenComparingLong(WriteEffectSpec::revision))
                .toList();
        if (writeEffects.isEmpty() || writeEffects.size() > 256
                || writeEffects.stream().map(WriteEffectSpecIntegrity::reference)
                .distinct().count() != writeEffects.size()) {
            throw new IllegalArgumentException(
                    "session payload requires between 1 and 256 unique write effects");
        }
        state = Objects.requireNonNull(state, "state");
        if (!state.sessionId().matches(
                "[A-Za-z0-9][A-Za-z0-9@._:-]{0,511}")) {
            throw new IllegalArgumentException(
                    "session payload requires an HTTP path-safe session id");
        }
        fingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                fingerprint, "session payload fingerprint");
    }

    /** @return a copy carrying a replacement state and no aggregate fingerprint */
    public MirrorSessionPayload withState(SessionStateSpace value) {
        return new MirrorSessionPayload(
                schemaVersion, stateModel, writeEffects, value, "");
    }

    /** @return a copy carrying a replacement canonical fingerprint */
    public MirrorSessionPayload withFingerprint(String value) {
        return new MirrorSessionPayload(
                schemaVersion, stateModel, writeEffects, state, value);
    }

    private static String version(String value) {
        String normalized = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported mirror session payload schemaVersion");
        }
        return normalized;
    }
}
