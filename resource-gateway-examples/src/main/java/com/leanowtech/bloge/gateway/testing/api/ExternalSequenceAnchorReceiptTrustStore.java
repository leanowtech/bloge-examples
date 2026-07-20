package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Atomic key snapshot used to verify external sequence-anchor receipts.
 *
 * <p>Implementations may be static or backed by a signed, dynamically refreshed trust
 * publication. Verification sees one immutable generation and must fail closed when freshness,
 * key lifecycle, refresh state, or durable rollback protection is ambiguous.</p>
 */
public interface ExternalSequenceAnchorReceiptTrustStore extends AutoCloseable {

    /** Verifies one receipt signature and its complete validity window. */
    void verify(
            TestSuiteStabilityExternalSequenceCheckpointReceipt receipt,
            Instant observedAt);

    /** @return true only when the snapshot covers every configured notary authority */
    boolean coversAuthorities(Set<String> authorityIds);

    /** @return aggregate key-free trust configuration and readiness facts */
    Descriptor descriptor();

    /** @return aggregate key-free refresh and lifecycle state */
    Snapshot snapshot();

    /** Static stores have no owned resources. */
    @Override
    default void close() {
    }

    /**
     * Aggregate trust capability facts.
     *
     * @param schemaVersion descriptor generation
     * @param available whether receipts can currently be verified
     * @param managedPublication whether keys come from a signed publication
     * @param restartFreeRotation whether a new publication can become visible without restart
     * @param durableFloor whether publication rollback is guarded across process restart
     * @param authorityCount distinct configured notary authorities
     * @param activeAuthorityCount authorities with at least one currently active key
     */
    record Descriptor(
            String schemaVersion,
            boolean available,
            boolean managedPublication,
            boolean restartFreeRotation,
            boolean durableFloor,
            int authorityCount,
            int activeAuthorityCount) {

        /** Current aggregate trust descriptor protocol. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorReceiptTrustDescriptor.v1";

        /** Rejects contradictory capability claims. */
        public Descriptor {
            schemaVersion = normalized(schemaVersion);
            if (!SCHEMA_VERSION.equals(schemaVersion) || authorityCount < 0
                    || activeAuthorityCount < 0 || activeAuthorityCount > authorityCount
                    || restartFreeRotation && !managedPublication
                    || durableFloor && !managedPublication
                    || available && activeAuthorityCount == 0) {
                throw new IllegalArgumentException(
                        "Invalid external sequence-anchor receipt trust descriptor");
            }
        }
    }

    /**
     * Aggregate process-local trust state without identities or key material.
     *
     * @param schemaVersion snapshot generation
     * @param available whether the latest complete snapshot is usable
     * @param status bounded refresh/lifecycle state
     * @param publicationSequence accepted managed generation, zero for static trust
     * @param authorityCount distinct notary authorities
     * @param activeAuthorityCount authorities with at least one active key
     * @param lastSuccessfulRefreshAt last successful managed refresh, null for static trust
     * @param refreshSuccessCount successful managed refresh count
     * @param refreshFailureCount failed managed refresh count
     */
    record Snapshot(
            String schemaVersion,
            boolean available,
            String status,
            long publicationSequence,
            int authorityCount,
            int activeAuthorityCount,
            Instant lastSuccessfulRefreshAt,
            long refreshSuccessCount,
            long refreshFailureCount) {

        /** Current aggregate trust snapshot protocol. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorReceiptTrustSnapshot.v1";

        /** Enforces bounded, key-free state. */
        public Snapshot {
            schemaVersion = normalized(schemaVersion);
            status = normalized(status);
            if (!SCHEMA_VERSION.equals(schemaVersion) || status.isEmpty()
                    || publicationSequence < 0 || authorityCount < 0
                    || activeAuthorityCount < 0 || activeAuthorityCount > authorityCount
                    || refreshSuccessCount < 0 || refreshFailureCount < 0
                    || available && activeAuthorityCount == 0) {
                throw new IllegalArgumentException(
                        "Invalid external sequence-anchor receipt trust snapshot");
            }
        }
    }

    /** Stable payload-free verification failure. */
    final class TrustException extends IllegalStateException {

        /** Bounded failure families safe for aggregate telemetry. */
        public enum Reason {
            UNAVAILABLE,
            UNKNOWN_KEY,
            KEY_INACTIVE,
            INVALID_SIGNATURE,
            CLOSED
        }

        private final Reason reason;

        /** Creates a failure without key, endpoint, receipt, or publication material. */
        public TrustException(Reason reason) {
            super("External sequence-anchor receipt trust rejected verification: "
                    + Objects.requireNonNull(reason, "reason"));
            this.reason = reason;
        }

        /** @return stable payload-free failure family */
        public Reason reason() {
            return reason;
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
