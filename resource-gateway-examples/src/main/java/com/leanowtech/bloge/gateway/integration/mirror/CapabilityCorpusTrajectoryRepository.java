package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.Optional;

/**
 * Full-scope append-only store for owner-reviewed trajectory publications.
 *
 * <p>Trajectory lineage is independent from corpus candidate and publication lineage. Every
 * serving lookup must bind an exact trajectory revision to the exact current corpus publication
 * it references; a newer corpus head never silently retargets an older trajectory.</p>
 */
public interface CapabilityCorpusTrajectoryRepository {
    /**
     * Appends one trajectory publication or recovers an exact retry.
     *
     * @param publication canonical immutable trajectory publication
     * @return committed or idempotently recovered publication
     * @throws Violation when lineage, identity, or canonical invariants fail
     */
    CapabilityCorpusTrajectoryPublication append(
            CapabilityCorpusTrajectoryPublication publication);

    /**
     * Reads one exact trajectory revision.
     *
     * @param scope complete enterprise scope
     * @param trajectoryId stable trajectory identity
     * @param revision positive trajectory revision
     * @return verified trajectory publication, or empty
     */
    Optional<CapabilityCorpusTrajectoryPublication> find(
            CapabilitySnapshot.Scope scope, String trajectoryId, long revision);

    /**
     * Reads the highest trajectory revision.
     *
     * @param scope complete enterprise scope
     * @param trajectoryId stable trajectory identity
     * @return verified current trajectory publication, or empty
     */
    Optional<CapabilityCorpusTrajectoryPublication> findLatest(
            CapabilitySnapshot.Scope scope, String trajectoryId);

    /** Closed persistence rejection vocabulary. */
    enum Reason {
        /** Artifact fingerprint is invalid. */
        CANONICAL_INVALID,
        /** Artifact identity or source command is inconsistent. */
        IDENTITY_MISMATCH,
        /** Revision does not append to the current trajectory head. */
        LINEAGE_CONFLICT,
        /** An exact coordinate is already bound to different content. */
        CONTENT_CONFLICT,
        /** Persisted JSON or duplicated index state is corrupt. */
        STORED_STATE_CORRUPT
    }

    /** Payload-free repository invariant failure. */
    final class Violation extends RuntimeException {
        private final Reason reason;

        /**
         * Creates one closed repository failure.
         *
         * @param reason stable persistence reason
         */
        public Violation(Reason reason) {
            super("Capability trajectory repository rejected: "
                    + Objects.requireNonNull(reason, "reason").name());
            this.reason = reason;
        }

        /**
         * Returns the stable rejection reason.
         *
         * @return repository reason
         */
        public Reason reason() {
            return reason;
        }
    }
}
