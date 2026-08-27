package com.leanowtech.bloge.gateway.testing.world.fidelity;

import java.util.Optional;

/** CAS and immutable-history port for World-specific drift governance. */
public interface WorldFidelityDriftRepository {
    Optional<DriftAnnotation> current(String tenantId, String targetFingerprint);

    void append(String tenantId, WorldFidelityReport report);

    boolean compareAndSet(String tenantId, String targetFingerprint, DriftState expected, DriftAnnotation next);

    /** Atomically advances the exact state and consumes a receipt only if the CAS wins. */
    boolean compareAndSetAndConsumeReceipt(String tenantId, String targetFingerprint,
                                            DriftState expected, DriftAnnotation next,
                                            String receiptFingerprint);

    boolean consumeReceipt(String tenantId, String receiptFingerprint);

    /** Returns immutable report history for one tenant and target. */
    java.util.List<WorldFidelityReport> history(String tenantId, String targetFingerprint);

    enum DriftState { CURRENT, SUSPECTED, CONFIRMED, REMEDIATING, ACCEPTED_DIVERGENCE }

    record DriftAnnotation(DriftState state, String reportFingerprint, String targetFingerprint,
                           String contractFingerprint, String worldSliceFingerprint,
                           String implementationFingerprint, String sampleSetFingerprint) {
        public DriftAnnotation {
            if (state == null || !safe(reportFingerprint) || !safe(targetFingerprint)
                    || !safe(contractFingerprint) || !safe(worldSliceFingerprint)
                    || !safe(implementationFingerprint) || !safe(sampleSetFingerprint)) {
                throw new WorldFidelityException(WorldFidelityException.Code.INVALID_INPUT);
            }
        }
        private static boolean safe(String value) { return value != null && value.matches("sha256:[0-9a-f]{64}"); }
    }
}
