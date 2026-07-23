package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.Optional;

/**
 * Full-scope append-only store for corpus revisions and serving publications.
 *
 * <p>Revision lineage and publication lineage are independent monotonic sequences. A candidate
 * does not become serving merely because it is the latest corpus revision; only the latest
 * verified publication may be consumed by a resolver. Every lookup requires complete enterprise
 * scope.</p>
 */
public interface CapabilityCorpusRepository {
    /**
     * Appends one corpus revision or recovers an exact retry.
     *
     * @param revision canonical immutable revision
     * @return committed or idempotently recovered revision
     * @throws Violation when lineage, identity, or canonical invariants fail
     */
    CapabilityCorpusRevision appendRevision(CapabilityCorpusRevision revision);

    /**
     * Reads one exact corpus revision.
     *
     * @param scope complete enterprise scope
     * @param corpusId stable corpus id
     * @param revision positive revision
     * @return verified revision, or empty
     */
    Optional<CapabilityCorpusRevision> findRevision(
            CapabilitySnapshot.Scope scope, String corpusId, long revision);

    /**
     * Reads the highest corpus revision.
     *
     * @param scope complete enterprise scope
     * @param corpusId stable corpus id
     * @return verified latest revision, or empty
     */
    Optional<CapabilityCorpusRevision> findLatestRevision(
            CapabilitySnapshot.Scope scope, String corpusId);

    /**
     * Appends one owner-reviewed serving publication or recovers an exact retry.
     *
     * @param publication canonical immutable publication
     * @return committed or idempotently recovered publication
     * @throws Violation when lineage, identity, or canonical invariants fail
     */
    CapabilityCorpusPublication appendPublication(
            CapabilityCorpusPublication publication);

    /**
     * Reads one exact publication generation.
     *
     * @param scope complete enterprise scope
     * @param corpusId stable corpus id
     * @param revision positive publication generation
     * @return verified publication, or empty
     */
    Optional<CapabilityCorpusPublication> findPublication(
            CapabilitySnapshot.Scope scope, String corpusId, long revision);

    /**
     * Reads the latest serving publication.
     *
     * @param scope complete enterprise scope
     * @param corpusId stable corpus id
     * @return verified latest publication, or empty
     */
    Optional<CapabilityCorpusPublication> findLatestPublication(
            CapabilitySnapshot.Scope scope, String corpusId);

    /** Closed persistence rejection vocabulary. */
    enum Reason {
        /** Artifact fingerprint is invalid. */
        CANONICAL_INVALID,
        /** Artifact identity or source command is inconsistent. */
        IDENTITY_MISMATCH,
        /** Revision or publication does not append to the current head. */
        LINEAGE_CONFLICT,
        /** An exact coordinate is already bound to different content. */
        CONTENT_CONFLICT,
        /** Persisted JSON or duplicated index state is corrupt. */
        STORED_STATE_CORRUPT
    }

    /** Payload-free repository invariant failure. */
    final class Violation extends RuntimeException {
        /** Stable machine-readable rejection reason. */
        private final Reason reason;

        /**
         * Creates a closed repository failure.
         *
         * @param reason stable persistence reason
         */
        public Violation(Reason reason) {
            super("Capability corpus repository rejected: "
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
