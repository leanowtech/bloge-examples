package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * External non-equivocation authority for monotonic test-control sequence heads.
 *
 * <p>The authority sits outside the rollbackable Resource Gateway database. A successful call
 * means an independently trusted compare-and-append quorum has accepted the exact head. Callers
 * must invoke it before their local durable floor, so an uncertain local commit can be retried
 * against an already anchored external head without creating an unanchored local generation.</p>
 */
public interface TestSuiteStabilityExternalSequenceAnchor extends AutoCloseable {

    /** Closed aggregate descriptor vocabulary; identities and chain material are forbidden. */
    Set<String> DESCRIPTOR_PROPERTIES = Set.of(
            "sourceType", "externalFirstCommit", "authenticatedConflictFatal",
            "concurrentNotaryRequests", "managedTrustPublication",
            "restartFreeNotaryKeyRotation", "durableTrustPublicationFloor",
            "notaryTransportSystemTrustStore", "notaryTransportPinnedMutualTls",
            "notaryTransportCertificateIdentityBound",
            "managedTrustTransportConfigured", "managedTrustTransportPinnedMutualTls",
            "managedTrustTransportCertificateIdentityBound",
            "bootstrapRootTransportConfigured", "bootstrapRootTransportPinnedMutualTls",
            "bootstrapRootTransportCertificateIdentityBound");

    /** Anchors one exact stream head or throws before local durable state may advance. */
    void accept(Head head);

    /** @return aggregate key-free configuration descriptor without endpoint or trust identities */
    Descriptor descriptor();

    /** @return aggregate runtime state; reading it must never perform remote I/O */
    Snapshot snapshot();

    /**
     * Returns payload-free transport posture for notaries and optional managed trust sources.
     *
     * @return immutable local transport-security projection without remote I/O
     */
    default ExternalSequenceAnchorTransportSecurity transportSecurity() {
        return ExternalSequenceAnchorTransportSecurity.compatibility();
    }

    /**
     * @return aggregate receipt-trust refresh state; reading it must never perform remote I/O
     */
    default ExternalSequenceAnchorReceiptTrustStore.Snapshot trustSnapshot() {
        return new ExternalSequenceAnchorReceiptTrustStore.Snapshot(
                ExternalSequenceAnchorReceiptTrustStore.Snapshot.SCHEMA_VERSION,
                false, "UNAVAILABLE", 0, 0, 0, null, 0, 0);
    }

    /** @return aggregate bootstrap-root capability without identities or key material */
    default ExternalSequenceAnchorBootstrapRootTrustStore.Descriptor
            bootstrapRootDescriptor() {
        return ExternalSequenceAnchorBootstrapRootTrustStore.unavailableDescriptor();
    }

    /** @return aggregate bootstrap-root chain state without remote I/O */
    default ExternalSequenceAnchorBootstrapRootTrustStore.Snapshot bootstrapRootSnapshot() {
        return ExternalSequenceAnchorBootstrapRootTrustStore.unavailableSnapshot();
    }

    /** Static or unavailable implementations own no refresh resources. */
    @Override
    default void close() {
    }

    /** Streams whose ordering is independently anchored. */
    enum StreamKind {
        SERVING_INVENTORY_PUBLICATION,
        SERVING_INVENTORY_TRUST_ROOT
    }

    /**
     * Exact private sequence head submitted to the external compare-and-append quorum.
     *
     * @param schemaVersion head protocol version
     * @param streamKind closed ordering stream family
     * @param scopeId stable fleet scope
     * @param streamId stable stream identity inside the scope
     * @param sequence contiguous one-based sequence
     * @param headFingerprint deterministic current head identity
     * @param previousHeadFingerprint deterministic predecessor, blank only at genesis
     */
    record Head(
            String schemaVersion,
            StreamKind streamKind,
            String scopeId,
            String streamId,
            long sequence,
            String headFingerprint,
            String previousHeadFingerprint) {

        /** Current external sequence-head protocol. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityExternalSequenceHead.v1";

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Enforces a canonical genesis or successor shape. */
        public Head {
            schemaVersion = normalized(schemaVersion);
            scopeId = normalized(scopeId);
            streamId = normalized(streamId);
            headFingerprint = normalized(headFingerprint);
            previousHeadFingerprint = normalized(previousHeadFingerprint);
            boolean predecessorShape = sequence == 1 && previousHeadFingerprint.isEmpty()
                    || sequence > 1 && FINGERPRINT.matcher(previousHeadFingerprint).matches();
            if (!SCHEMA_VERSION.equals(schemaVersion) || streamKind == null
                    || !IDENTIFIER.matcher(scopeId).matches()
                    || !IDENTIFIER.matcher(streamId).matches() || sequence < 1
                    || !FINGERPRINT.matcher(headFingerprint).matches() || !predecessorShape) {
                throw new IllegalArgumentException("Invalid external sequence head");
            }
        }
    }

    /**
     * Aggregate configuration facts safe for capability projection.
     *
     * @param schemaVersion descriptor protocol version
     * @param available whether the configured authority can be invoked
     * @param externallyDurable whether state lives outside the Resource Gateway database
     * @param challengeBound whether every receipt binds fresh request entropy
     * @param byzantineQuorum whether the declared fault model tolerates at least one faulty notary
     * @param authorityCount configured independent notary count
     * @param signatureThreshold accepted receipt quorum
     * @param maximumFaults declared Byzantine fault bound
     * @param independentFailureDomainCount distinct configured failure domains
     * @param properties closed aggregate implementation metadata
     */
    record Descriptor(
            String schemaVersion,
            boolean available,
            boolean externallyDurable,
            boolean challengeBound,
            boolean byzantineQuorum,
            int authorityCount,
            int signatureThreshold,
            int maximumFaults,
            int independentFailureDomainCount,
            Map<String, Object> properties) {

        /** Current aggregate descriptor protocol. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityExternalSequenceAnchorDescriptor.v1";

        /** Rejects capability claims that do not satisfy their own quorum math. */
        public Descriptor {
            schemaVersion = normalized(schemaVersion);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
            boolean quorumShape = maximumFaults >= 0
                    && authorityCount >= 3 * maximumFaults + 1
                    && signatureThreshold >= 2 * maximumFaults + 1
                    && signatureThreshold <= authorityCount
                    && independentFailureDomainCount == authorityCount;
            if (!SCHEMA_VERSION.equals(schemaVersion) || authorityCount < 0
                    || signatureThreshold < 0 || independentFailureDomainCount < 0
                    || !DESCRIPTOR_PROPERTIES.containsAll(properties.keySet())
                    || properties.size() > DESCRIPTOR_PROPERTIES.size()
                    || properties.entrySet().stream().anyMatch(
                    entry -> !safeDescriptorValue(entry.getKey(), entry.getValue()))
                    || available && (!externallyDurable || !challengeBound || !quorumShape)
                    || byzantineQuorum != (available && maximumFaults > 0)) {
                throw new IllegalArgumentException("Invalid external sequence-anchor descriptor");
            }
        }
    }

    /**
     * Aggregate runtime state without stream, endpoint, key, fingerprint, or challenge material.
     *
     * @param schemaVersion snapshot protocol version
     * @param available whether the latest operation left the anchor usable
     * @param status bounded status family
     * @param lastSuccessfulAnchorAt last successful quorum observation
     * @param successCount successful process-local operations
     * @param failureCount unavailable or invalid-quorum operations
     * @param conflictCount authenticated external conflict operations
     * @param authorityCount configured authority count
     * @param signatureThreshold configured quorum
     * @param maximumFaults configured Byzantine fault bound
     * @param independentFailureDomainCount configured independent domain count
     */
    record Snapshot(
            String schemaVersion,
            boolean available,
            String status,
            Instant lastSuccessfulAnchorAt,
            long successCount,
            long failureCount,
            long conflictCount,
            int authorityCount,
            int signatureThreshold,
            int maximumFaults,
            int independentFailureDomainCount) {

        /** Current aggregate runtime snapshot protocol. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityExternalSequenceAnchorSnapshot.v1";

        /** Enforces bounded, key-free operational state. */
        public Snapshot {
            schemaVersion = normalized(schemaVersion);
            status = normalized(status);
            boolean emptyConfiguration = authorityCount == 0 && signatureThreshold == 0
                    && maximumFaults == 0 && independentFailureDomainCount == 0;
            boolean quorumShape = authorityCount >= 3 * maximumFaults + 1
                    && signatureThreshold >= 2 * maximumFaults + 1
                    && signatureThreshold <= authorityCount
                    && independentFailureDomainCount == authorityCount;
            if (!SCHEMA_VERSION.equals(schemaVersion) || status.isEmpty()
                    || successCount < 0 || failureCount < 0 || conflictCount < 0
                    || authorityCount < 0 || signatureThreshold < 0 || maximumFaults < 0
                    || independentFailureDomainCount < 0
                    || !emptyConfiguration && !quorumShape
                    || available && emptyConfiguration) {
                throw new IllegalArgumentException("Invalid external sequence-anchor snapshot");
            }
        }
    }

    /** @return fail-closed anchor for profiles without external non-equivocation */
    static TestSuiteStabilityExternalSequenceAnchor unavailable() {
        return new TestSuiteStabilityExternalSequenceAnchor() {
            private final Descriptor descriptor = new Descriptor(
                    Descriptor.SCHEMA_VERSION, false, false, false, false,
                    0, 0, 0, 0, Map.of("sourceType", "UNAVAILABLE"));
            private final Snapshot snapshot = new Snapshot(
                    Snapshot.SCHEMA_VERSION, false, "UNAVAILABLE", null,
                    0, 0, 0, 0, 0, 0, 0);

            @Override
            public void accept(Head head) {
                Objects.requireNonNull(head, "head");
                throw new ExternalAnchorException(
                        ExternalAnchorException.Reason.UNAVAILABLE);
            }

            @Override
            public Descriptor descriptor() {
                return descriptor;
            }

            @Override
            public Snapshot snapshot() {
                return snapshot;
            }
        };
    }

    /** Fail-closed protocol error without remote diagnostics or chain material. */
    final class ExternalAnchorException extends IllegalStateException {

        /** Stable failure families safe for logs and health. */
        public enum Reason {
            UNAVAILABLE,
            QUORUM_NOT_MET,
            AUTHENTICATED_CONFLICT,
            CLOSED
        }

        private final Reason reason;

        /** Creates a bounded failure without a remote response body. */
        public ExternalAnchorException(Reason reason) {
            super("External sequence anchor rejected the candidate: "
                    + Objects.requireNonNull(reason, "reason"));
            this.reason = reason;
        }

        /** @return stable payload-free failure family */
        public Reason reason() {
            return reason;
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static boolean safeDescriptorValue(String key, Object value) {
        return "transportSecurity".equals(key)
                ? ExternalSequenceAnchorTransportSecurity.isValidProjection(value)
                : value instanceof Boolean
                || value instanceof String text && !text.isBlank() && text.length() <= 128
                && text.chars().noneMatch(Character::isISOControl);
    }
}
