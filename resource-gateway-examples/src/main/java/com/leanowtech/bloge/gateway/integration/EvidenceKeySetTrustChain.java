package com.leanowtech.bloge.gateway.integration;

import java.util.HashSet;
import java.util.Set;

/** Shared append-time invariants for one externally signed evidence trust log. */
final class EvidenceKeySetTrustChain {
    private EvidenceKeySetTrustChain() {
    }

    /**
     * Requires a publication to be the unique legal successor of the current head.
     *
     * @param previous current head, or null for an empty log
     * @param next candidate successor
     * @param permanentlyRevoked all fingerprints revoked earlier in the log
     */
    static void requireNext(EvidenceKeySetTrustPublication previous,
                            EvidenceKeySetTrustPublication next,
                            Set<String> permanentlyRevoked) {
        if (next == null) {
            throw new ChainViolation(Reason.MATERIAL_INVALID);
        }
        Set<String> revokedHistory = permanentlyRevoked == null
                ? Set.of() : Set.copyOf(permanentlyRevoked);
        Set<String> accepted = new HashSet<>();
        Set<String> newlyRevoked = new HashSet<>();
        for (EvidenceKeySetTrustPublication.SnapshotPin pin : next.pins()) {
            if (pin.state() == EvidenceKeySetTrustPublication.PinState.REVOKED) {
                if (!revokedHistory.contains(pin.snapshotFingerprint())) {
                    newlyRevoked.add(pin.snapshotFingerprint());
                }
            } else {
                accepted.add(pin.snapshotFingerprint());
            }
        }
        if (!java.util.Collections.disjoint(accepted, revokedHistory)) {
            throw new ChainViolation(Reason.REVOKED_PIN_REACTIVATED);
        }
        if (previous == null) {
            if (next.sequence() != 1 || !next.previousPublicationFingerprint().isBlank()
                    || next.recoveryEpoch() != (newlyRevoked.isEmpty() ? 0 : 1)) {
                throw new ChainViolation(Reason.GENESIS_INVALID);
            }
            return;
        }
        if (!previous.trustDomain().equals(next.trustDomain())
                || !previous.logId().equals(next.logId())) {
            throw new ChainViolation(Reason.IDENTITY_MISMATCH);
        }
        if (next.sequence() != previous.sequence() + 1) {
            throw new ChainViolation(Reason.SEQUENCE_GAP);
        }
        if (!previous.publicationFingerprint().equals(next.previousPublicationFingerprint())) {
            throw new ChainViolation(Reason.PREVIOUS_FINGERPRINT_MISMATCH);
        }
        if (next.publishedAt().isBefore(previous.publishedAt())) {
            throw new ChainViolation(Reason.TIME_ROLLBACK);
        }
        long expectedRecoveryEpoch = newlyRevoked.isEmpty()
                ? previous.recoveryEpoch() : previous.recoveryEpoch() + 1;
        if (next.recoveryEpoch() != expectedRecoveryEpoch) {
            throw new ChainViolation(Reason.RECOVERY_EPOCH_INVALID);
        }
    }

    /** Stable chain-rejection reasons used by repositories and integration errors. */
    enum Reason {
        MATERIAL_INVALID,
        GENESIS_INVALID,
        IDENTITY_MISMATCH,
        SEQUENCE_GAP,
        PREVIOUS_FINGERPRINT_MISMATCH,
        TIME_ROLLBACK,
        RECOVERY_EPOCH_INVALID,
        REVOKED_PIN_REACTIVATED,
        SEQUENCE_FORK
    }

    /** Bounded chain conflict without publication payloads. */
    static final class ChainViolation extends IllegalArgumentException {
        private final Reason reason;

        ChainViolation(Reason reason) {
            super("Evidence trust publication chain rejected: " + reason);
            this.reason = reason;
        }

        Reason reason() {
            return reason;
        }
    }
}
