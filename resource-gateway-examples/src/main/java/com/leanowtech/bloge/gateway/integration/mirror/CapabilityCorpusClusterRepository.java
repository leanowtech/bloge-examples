package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.Optional;

/**
 * Full-scope append-only store for owner-reviewed cluster publications.
 *
 * <p>Cluster lineage is independent from corpus and trajectory lineages. Exact retries are
 * idempotent; a new corpus head or validation generation never silently retargets a stored
 * cluster.</p>
 */
public interface CapabilityCorpusClusterRepository {
    /**
     * Appends one cluster publication or recovers an exact retry.
     *
     * @param publication canonical immutable cluster publication
     * @return committed or idempotently recovered publication
     * @throws Violation when lineage, identity, or canonical invariants fail
     */
    CapabilityCorpusClusterPublication append(
            CapabilityCorpusClusterPublication publication);

    /**
     * Reads one exact cluster revision.
     *
     * @param scope complete enterprise scope
     * @param clusterId stable cluster identity
     * @param revision positive cluster revision
     * @return verified cluster publication, or empty
     */
    Optional<CapabilityCorpusClusterPublication> find(
            CapabilitySnapshot.Scope scope, String clusterId, long revision);

    /**
     * Reads the highest cluster revision.
     *
     * @param scope complete enterprise scope
     * @param clusterId stable cluster identity
     * @return verified current cluster publication, or empty
     */
    Optional<CapabilityCorpusClusterPublication> findLatest(
            CapabilitySnapshot.Scope scope, String clusterId);

    /** Closed persistence rejection vocabulary. */
    enum Reason {
        /** Artifact fingerprint is invalid. */
        CANONICAL_INVALID,
        /** Artifact identity or source command is inconsistent. */
        IDENTITY_MISMATCH,
        /** Revision does not append to the current cluster head. */
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
            super("Capability cluster repository rejected: "
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
