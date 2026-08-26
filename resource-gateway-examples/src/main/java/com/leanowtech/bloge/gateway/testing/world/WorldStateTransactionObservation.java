package com.leanowtech.bloge.gateway.testing.world;

import java.util.List;

/** Payload-free canonical observation of one committed world transition. */
public record WorldStateTransactionObservation(
        WorldInvocationCoordinate coordinate,
        List<String> readKeys,
        List<String> writeKeys,
        String readFingerprint,
        String writeFingerprint,
        String resultFingerprint
) {
    private static final String FINGERPRINT = "sha256:[0-9a-f]{64}";

    public WorldStateTransactionObservation {
        if (coordinate == null
                || readFingerprint == null || !readFingerprint.matches(FINGERPRINT)
                || writeFingerprint == null || !writeFingerprint.matches(FINGERPRINT)
                || resultFingerprint == null || !resultFingerprint.matches(FINGERPRINT)
                || readKeys == null || writeKeys == null
                || readKeys.stream().anyMatch(key -> key == null || key.isBlank())
                || writeKeys.stream().anyMatch(key -> key == null || key.isBlank())) {
            throw new WorldModelException(WorldModelException.Code.STATE_TRANSACTION_INVALID);
        }
        try {
            readKeys = readKeys.stream().map(StatePointer::normalize).sorted().distinct().toList();
            writeKeys = writeKeys.stream().map(StatePointer::normalize).sorted().distinct().toList();
        } catch (RuntimeException invalid) {
            throw new WorldModelException(WorldModelException.Code.STATE_TRANSACTION_INVALID);
        }
    }
}
