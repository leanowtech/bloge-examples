package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.Optional;

/**
 * Full-scope append-only store for terminal quarantine reviews.
 *
 * <p>One observation may have at most one review artifact. Exact retries return the original
 * review; a different command for the same observation conflicts. The repository never offers a
 * tenant-wide lookup and never updates the original observation or admission.</p>
 */
public interface CapabilityObservationReviewRepository {
    /**
     * Appends one terminal review or recovers an identical stored review.
     *
     * @param review canonical review artifact
     * @return committed or idempotently recovered review
     * @throws Violation when canonical, identity, or immutable-id invariants fail
     */
    CapabilityObservationReview append(CapabilityObservationReview review);

    /**
     * Reads the review for one observation inside an exact enterprise scope.
     *
     * @param scope complete enterprise scope
     * @param observationId exact observation id
     * @return verified terminal review, or empty
     * @throws Violation when stored state fails integrity validation
     */
    Optional<CapabilityObservationReview> find(
            CapabilitySnapshot.Scope scope, String observationId);

    /** Closed persistence rejection vocabulary. */
    enum Reason {
        /** Candidate review fingerprint is invalid. */
        CANONICAL_INVALID,
        /** Review identity or index coordinates are invalid. */
        IDENTITY_MISMATCH,
        /** The observation is already bound to another review command. */
        REVIEW_CONFLICT,
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
            super("Capability observation review repository rejected: "
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
