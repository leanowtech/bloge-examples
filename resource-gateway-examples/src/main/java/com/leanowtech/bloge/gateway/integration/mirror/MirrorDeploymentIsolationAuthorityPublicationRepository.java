package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.Optional;

/**
 * Append-only trusted distribution boundary for deployment-isolation authority publications.
 *
 * <p>A stream is identified by the complete enterprise scope, the immutable deployment identity,
 * and the key-set id. Implementations must commit the publication body and its monotonic trusted
 * floor atomically. Historical rows are immutable; serving methods expose only the current floor
 * so an API caller cannot use an unpinned historical lookup as a rollback primitive.</p>
 */
public interface MirrorDeploymentIsolationAuthorityPublicationRepository {
    /**
     * Appends one canonical successor or returns the identical generation already committed.
     *
     * @param publication canonical threshold-signed publication
     * @return newly committed or idempotently recovered publication
     * @throws Violation when identity, integrity, or monotonic-chain invariants fail
     */
    MirrorDeploymentIsolationAuthorityKeySetPublication append(
            MirrorDeploymentIsolationAuthorityKeySetPublication publication);

    /**
     * Reads the publication at the current durable floor.
     *
     * @param stream exact locally trusted stream identity
     * @return current publication, or empty before bootstrap
     */
    Optional<MirrorDeploymentIsolationAuthorityKeySetPublication> latest(StreamIdentity stream);

    /**
     * Reads a content-addressed publication only when it is still the current trusted floor.
     *
     * @param stream exact locally trusted stream identity
     * @param generation exact expected current generation
     * @param publicationFingerprint exact expected current fingerprint
     * @return current publication when all coordinates match
     */
    Optional<MirrorDeploymentIsolationAuthorityKeySetPublication> current(
            StreamIdentity stream, long generation, String publicationFingerprint);

    /**
     * Reads the durable anti-rollback floor for verification before a candidate append.
     *
     * @param stream exact locally trusted stream identity
     * @return trusted floor, or empty before bootstrap
     */
    Optional<MirrorDeploymentIsolationAuthorityKeySetIntegrity.TrustedFloor> floor(
            StreamIdentity stream);

    /**
     * Exact full-scope identity of one publication stream.
     *
     * @param scope complete enterprise scope
     * @param deployment immutable workload generation
     * @param keySetId stable key-set stream id
     */
    record StreamIdentity(
            CapabilitySnapshot.Scope scope,
            MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
            String keySetId
    ) {
        /** Validates complete identity coordinates before any database lookup. */
        public StreamIdentity {
            scope = Objects.requireNonNull(scope, "scope");
            deployment = Objects.requireNonNull(deployment, "deployment");
            keySetId = required(keySetId, "keySetId");
        }

        /**
         * Creates the exact stream identity carried by a publication.
         *
         * @param publication canonical publication carrying complete stream coordinates
         * @return exact full-scope stream identity
         */
        public static StreamIdentity from(
                MirrorDeploymentIsolationAuthorityKeySetPublication publication) {
            Objects.requireNonNull(publication, "publication");
            return new StreamIdentity(publication.material().scope(),
                    publication.material().deployment(), publication.material().keySetId());
        }

        private static String required(String value, String field) {
            String exact = value == null ? "" : value.trim();
            if (exact.isBlank() || exact.length() > 512
                    || !exact.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}")) {
                throw new IllegalArgumentException(field + " is invalid");
            }
            return exact;
        }
    }

    /** Closed repository rejection vocabulary suitable for stable service mapping. */
    enum Reason {
        /** Candidate fingerprints or persisted canonical material are invalid. */
        CANONICAL_INVALID,
        /** Candidate scope, deployment, or key-set identity disagrees with its stream. */
        IDENTITY_MISMATCH,
        /** A non-generation-one publication attempted to bootstrap an empty stream. */
        BOOTSTRAP_GENERATION_INVALID,
        /** Candidate generation is below the durable floor. */
        GENERATION_ROLLBACK,
        /** Another fingerprint already occupies the candidate generation. */
        GENERATION_FORK,
        /** Candidate skipped one or more generations. */
        GENERATION_GAP,
        /** Candidate does not reference the current floor fingerprint. */
        PREDECESSOR_MISMATCH,
        /** The same content address was associated with a different immutable stream. */
        CONTENT_ADDRESS_CONFLICT,
        /** Persisted index, floor, or publication material is internally inconsistent. */
        STORED_STATE_CORRUPT
    }

    /** Repository invariant failure without publication payload or key material in its message. */
    final class Violation extends RuntimeException {
        /** Closed rejection category retained separately from the sanitized exception message. */
        private final Reason reason;

        /**
         * Creates one payload-free invariant failure.
         *
         * @param reason closed machine-readable rejection reason
         */
        public Violation(Reason reason) {
            super("Mirror isolation-authority publication repository rejected: "
                    + Objects.requireNonNull(reason, "reason").name());
            this.reason = reason;
        }

        /**
         * Returns the repository rejection category without exposing publication material.
         *
         * @return closed machine-readable rejection reason
         */
        public Reason reason() {
            return reason;
        }
    }
}
