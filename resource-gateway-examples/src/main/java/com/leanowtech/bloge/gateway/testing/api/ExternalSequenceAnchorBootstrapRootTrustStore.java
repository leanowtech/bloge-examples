package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Atomic bootstrap-root snapshot used to verify managed external-notary trust publications.
 *
 * <p>Implementations may hold one already verified chain or dynamically refresh a complete
 * genesis-to-head bundle. Verification must observe exactly one immutable root generation and
 * fail closed when freshness, lifecycle, refresh, or rollback state is ambiguous.</p>
 */
public interface ExternalSequenceAnchorBootstrapRootTrustStore extends AutoCloseable {

    /** Verifies the bootstrap signatures on one managed notary trust publication. */
    void verify(ExternalSequenceAnchorTrustPublication publication, Instant observedAt);

    /** Rejects authority or public-key overlap between root and receipt-signing trust domains. */
    void requireIndependentFrom(
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> notaryKeys);

    /** @return true only when the exact deployment binding belongs to this root chain */
    boolean matchesBinding(String scopeId, String rootSetId, String trustDomain);

    /** @return aggregate key-free capability facts */
    Descriptor descriptor();

    /** @return aggregate key-free chain and refresh state */
    Snapshot snapshot();

    /**
     * Returns transport posture only for a dynamically refreshed complete-chain source.
     *
     * @return payload-free transport descriptor, or empty for static/caller-owned roots
     */
    default Optional<ControlPlaneHttpTransport.Descriptor> transportDescriptor() {
        return Optional.empty();
    }

    /** @return an unavailable capability value for trust paths without a root-chain projection */
    static Descriptor unavailableDescriptor() {
        return new Descriptor(Descriptor.SCHEMA_VERSION, false, false, false,
                false, false, 0, 0, 0);
    }

    /** @return an unavailable state value for trust paths without a root-chain projection */
    static Snapshot unavailableSnapshot() {
        return new Snapshot(Snapshot.SCHEMA_VERSION, false, "UNAVAILABLE",
                0, 0, 0, 0, null, null, 0, 0);
    }

    /** Immutable configured stores have no owned resources. */
    @Override
    default void close() {
    }

    /** Bounded verification failure used by a dynamic store to trigger unknown-key refresh. */
    final class TrustException extends RuntimeException {
        private final Reason reason;

        /** Creates one payload-free root verification failure. */
        public TrustException(Reason reason) {
            super("External sequence-anchor bootstrap-root trust rejected publication: "
                    + java.util.Objects.requireNonNull(reason, "reason"));
            this.reason = reason;
        }

        /** @return bounded reason safe for local control flow */
        public Reason reason() {
            return reason;
        }

        /** Root verification rejection classes. */
        public enum Reason {
            UNKNOWN_KEY,
            INVALID_SIGNATURE,
            UNAVAILABLE,
            CLOSED
        }
    }

    /** Aggregate root-chain capability without identities or key material. */
    record Descriptor(
            String schemaVersion,
            boolean available,
            boolean managedChain,
            boolean restartFreeRotation,
            boolean completeGenesisReplay,
            boolean durableFloor,
            int authorityCount,
            int activeAuthorityCount,
            int signatureThreshold) {

        /** Current root-chain descriptor generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootTrustDescriptor.v1";

        /** Rejects contradictory or malformed aggregate capability claims. */
        public Descriptor {
            schemaVersion = normalized(schemaVersion);
            if (!SCHEMA_VERSION.equals(schemaVersion) || authorityCount < 0
                    || activeAuthorityCount < 0 || activeAuthorityCount > authorityCount
                    || signatureThreshold < 0 || signatureThreshold > authorityCount
                    || restartFreeRotation && !managedChain
                    || completeGenesisReplay && !managedChain
                    || durableFloor && !managedChain
                    || available && (activeAuthorityCount < signatureThreshold
                    || signatureThreshold == 0)) {
                throw new IllegalArgumentException(
                        "Invalid external sequence-anchor bootstrap-root descriptor");
            }
        }
    }

    /** Aggregate root-chain state without endpoint, key, signature, or fingerprint material. */
    record Snapshot(
            String schemaVersion,
            boolean available,
            String status,
            long headSequence,
            int transitionCount,
            int authorityCount,
            int activeAuthorityCount,
            Instant headExpiresAt,
            Instant lastSuccessfulRefreshAt,
            long refreshSuccessCount,
            long refreshFailureCount) {

        /** Current key-free root-chain snapshot generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootTrustSnapshot.v1";

        /** Enforces bounded and internally consistent diagnostics. */
        public Snapshot {
            schemaVersion = normalized(schemaVersion);
            status = normalized(status);
            if (!SCHEMA_VERSION.equals(schemaVersion) || status.isEmpty()
                    || headSequence < 0 || transitionCount < 0
                    || transitionCount > ExternalSequenceAnchorBootstrapRootBundle
                    .MAXIMUM_TRANSITIONS
                    || authorityCount < 0 || activeAuthorityCount < 0
                    || activeAuthorityCount > authorityCount
                    || refreshSuccessCount < 0 || refreshFailureCount < 0
                    || headSequence == 0 && transitionCount != 0
                    || headSequence > 0 && transitionCount == 0
                    || available && !((headSequence == 0 && transitionCount == 0
                    && headExpiresAt == null)
                    || (headSequence > 0 && transitionCount > 0
                    && headExpiresAt != null))) {
                throw new IllegalArgumentException(
                        "Invalid external sequence-anchor bootstrap-root snapshot");
            }
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
