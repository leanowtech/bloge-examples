package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.Optional;

/**
 * Append-only repository and durable anti-rollback floor for Shadow authority key sets.
 *
 * <p>Implementations must atomically persist each immutable publication and advance one
 * full-scope stream head. Reads expose only the current floor; arbitrary historical lookup is not
 * an authorization primitive.</p>
 */
public interface ReadOnlyShadowAuthorityKeySetRepository {
    /**
     * Appends one canonical legal successor.
     *
     * @param publication root-verified publication admitted by the application boundary
     * @return committed publication or identical idempotent replay
     */
    ReadOnlyShadowAuthorityKeySetPublication append(
            ReadOnlyShadowAuthorityKeySetPublication publication);

    /**
     * Reads the current publication.
     *
     * @param stream exact locally governed stream identity
     * @return current publication, or empty before bootstrap
     */
    Optional<ReadOnlyShadowAuthorityKeySetPublication> latest(StreamIdentity stream);

    /**
     * Reads the durable anti-rollback floor.
     *
     * @param stream exact locally governed stream identity
     * @return current floor, or empty before bootstrap
     */
    Optional<ReadOnlyShadowAuthorityKeySetIntegrity.TrustedFloor> floor(StreamIdentity stream);

    /** @return whether the durable publication log and floor tables are currently readable */
    boolean available();

    /**
     * Exact identity of one managed key-set stream.
     *
     * @param scope complete enterprise scope
     * @param publicationKind delegated authority protocol
     * @param issuer delegated authority identity
     * @param keySetId stable key-set stream
     */
    record StreamIdentity(
            CapabilitySnapshot.Scope scope,
            ReadOnlyShadowAuthorityIntegrity.PublicationKind publicationKind,
            String issuer,
            String keySetId
    ) {
        /** Validates complete stream identity. */
        public StreamIdentity {
            scope = ReadOnlyShadowAuthoritySeal.scope(scope, "scope");
            publicationKind = Objects.requireNonNull(publicationKind, "publicationKind");
            issuer = ReadOnlyShadowAuthoritySeal.identifier(issuer, "issuer");
            keySetId = ReadOnlyShadowAuthoritySeal.identifier(keySetId, "keySetId");
        }

        /** Creates stream coordinates from a canonical publication. */
        public static StreamIdentity from(
                ReadOnlyShadowAuthorityKeySetPublication publication) {
            Objects.requireNonNull(publication, "publication");
            return new StreamIdentity(publication.material().scope(),
                    publication.material().publicationKind(), publication.material().issuer(),
                    publication.material().keySetId());
        }
    }

    /** Stable payload-free repository rejection categories. */
    enum Reason {
        /** Candidate content addresses are invalid. */
        CANONICAL_INVALID,
        /** Candidate stream identity drifted. */
        IDENTITY_MISMATCH,
        /** Empty stream did not begin at generation one. */
        BOOTSTRAP_GENERATION_INVALID,
        /** Candidate moved behind the durable floor. */
        GENERATION_ROLLBACK,
        /** Different content claims one generation. */
        GENERATION_FORK,
        /** Candidate skipped one or more generations. */
        GENERATION_GAP,
        /** Candidate did not name the current predecessor. */
        PREDECESSOR_MISMATCH,
        /** Existing key material was changed, omitted, or illegally reactivated. */
        KEY_LIFECYCLE_INVALID,
        /** One content address was associated with different coordinates. */
        CONTENT_ADDRESS_CONFLICT,
        /** Concurrent genesis did not converge after a fresh transaction retry. */
        CONCURRENT_INITIALIZATION,
        /** Indexed coordinates and canonical JSON disagree. */
        STORED_STATE_CORRUPT
    }

    /** Repository invariant failure without publication or key material in its message. */
    final class Violation extends RuntimeException {
        private final Reason reason;

        /** Creates one bounded repository rejection. */
        public Violation(Reason reason) {
            super("Read-only Shadow authority key-set repository rejected: "
                    + Objects.requireNonNull(reason, "reason").name());
            this.reason = reason;
        }

        /** @return stable machine-readable rejection reason */
        public Reason reason() {
            return reason;
        }
    }
}
