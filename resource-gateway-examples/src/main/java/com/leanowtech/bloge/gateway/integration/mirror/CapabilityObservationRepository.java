package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.Optional;

/**
 * Full-scope append-only store for signed observations and terminal admission decisions.
 *
 * <p>The repository has no payload columns and no tenant-wide lookup. An observation id is
 * immutable inside the complete tenant, organization, project, environment, and region scope.
 * Exact retries recover the original decision; a different fingerprint at the same id is a
 * conflict rather than an update.</p>
 */
public interface CapabilityObservationRepository {
    /**
     * Appends an observation and decision atomically.
     *
     * @param candidate canonical signed envelope and sealed terminal decision
     * @return committed or idempotently recovered stored observation
     * @throws Violation when identity, content address, or immutable-id invariants fail
     */
    StoredObservation append(StoredObservation candidate);

    /**
     * Reads one exact observation inside a complete enterprise scope.
     *
     * @param scope complete enterprise scope
     * @param observationId exact observation id
     * @return verified stored observation, or empty
     * @throws Violation when persisted index or canonical material is corrupt
     */
    Optional<StoredObservation> find(
            CapabilitySnapshot.Scope scope, String observationId);

    /**
     * Atomic immutable observation and its terminal local decision.
     *
     * @param envelope signed producer observation
     * @param admission local admitted or quarantined decision
     */
    record StoredObservation(
            CapabilityObservationEnvelope envelope,
            CapabilityObservationAdmission admission
    ) {
        /** Enforces exact identity linkage between producer fact and local decision. */
        public StoredObservation {
            envelope = Objects.requireNonNull(envelope, "envelope");
            admission = Objects.requireNonNull(admission, "admission");
            if (!envelope.artifactRef().equals(admission.observationRef())
                    || !envelope.material().scope().equals(admission.scope())
                    || !envelope.material().capabilityRef().equals(
                    admission.capabilityRef())
                    || !envelope.material().dataUseGrant().grantRef().equals(
                    admission.dataUseGrantRef())) {
                throw new IllegalArgumentException(
                        "observation and admission identities do not match");
            }
        }
    }

    /** Closed persistence rejection vocabulary. */
    enum Reason {
        /** Candidate envelope or decision content addressing is invalid. */
        CANONICAL_INVALID,
        /** Candidate envelope and decision identities do not match. */
        IDENTITY_MISMATCH,
        /** An observation id is already bound to another immutable fingerprint. */
        OBSERVATION_ID_CONFLICT,
        /** Persisted index or canonical JSON failed integrity validation. */
        STORED_STATE_CORRUPT
    }

    /** Repository invariant failure with no payload or provider detail in its message. */
    final class Violation extends RuntimeException {
        /** Closed persistence reason safe to expose to application policy. */
        private final Reason reason;

        /**
         * Creates one payload-free repository failure.
         *
         * @param reason closed machine-readable reason
         */
        public Violation(Reason reason) {
            super("Capability observation repository rejected: "
                    + Objects.requireNonNull(reason, "reason").name());
            this.reason = reason;
        }

        /**
         * Returns the closed rejection reason.
         *
         * @return repository reason
         */
        public Reason reason() {
            return reason;
        }
    }
}
