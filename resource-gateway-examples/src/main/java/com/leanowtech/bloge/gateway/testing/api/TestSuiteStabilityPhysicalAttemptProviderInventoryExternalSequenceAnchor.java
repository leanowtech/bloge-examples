package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;

/**
 * Domain-isolated external non-equivocation port for physical provider-inventory generations.
 *
 * <p>The strict HTTP/quorum transport and its versioned sequence-head protocol are shared with
 * the mature stability control plane. The generic {@code SERVING_INVENTORY_PUBLICATION} stream
 * kind is an ordering class only; the physical product boundary is enforced by this distinct Java
 * type and the wrapper's domain-separated stream id. This prevents Spring from injecting an
 * external authority governed by another product's trust policy.</p>
 */
public interface TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor
        extends AutoCloseable {

    /** Anchors one exact composite publication/witness head before local state may advance. */
    void accept(TestSuiteStabilityExternalSequenceAnchor.Head head);

    /** @return aggregate key-free configuration descriptor */
    TestSuiteStabilityExternalSequenceAnchor.Descriptor descriptor();

    /** @return aggregate runtime state; this read must never perform remote I/O */
    TestSuiteStabilityExternalSequenceAnchor.Snapshot snapshot();

    /** @return payload-free notary and managed-trust transport posture */
    default ExternalSequenceAnchorTransportSecurity transportSecurity() {
        return ExternalSequenceAnchorTransportSecurity.compatibility();
    }

    /** @return aggregate receipt-trust refresh state without identities or key material */
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

    /** Caller-owned test adapters own no refresh resources by default. */
    @Override
    default void close() {
    }

    /**
     * Adapts the shared strict HTTP/quorum implementation behind the physical domain port.
     *
     * @param delegate externally durable challenge-bound sequence authority
     * @return physical-provider-inventory-scoped adapter that owns the delegate lifecycle
     */
    static TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor adapt(
            TestSuiteStabilityExternalSequenceAnchor delegate) {
        TestSuiteStabilityExternalSequenceAnchor value = Objects.requireNonNull(
                delegate, "delegate");
        return new TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor() {
            @Override
            public void accept(TestSuiteStabilityExternalSequenceAnchor.Head head) {
                value.accept(head);
            }

            @Override
            public TestSuiteStabilityExternalSequenceAnchor.Descriptor descriptor() {
                return value.descriptor();
            }

            @Override
            public TestSuiteStabilityExternalSequenceAnchor.Snapshot snapshot() {
                return value.snapshot();
            }

            @Override
            public ExternalSequenceAnchorTransportSecurity transportSecurity() {
                return value.transportSecurity();
            }

            @Override
            public ExternalSequenceAnchorReceiptTrustStore.Snapshot trustSnapshot() {
                return value.trustSnapshot();
            }

            @Override
            public ExternalSequenceAnchorBootstrapRootTrustStore.Descriptor
                    bootstrapRootDescriptor() {
                return value.bootstrapRootDescriptor();
            }

            @Override
            public ExternalSequenceAnchorBootstrapRootTrustStore.Snapshot
                    bootstrapRootSnapshot() {
                return value.bootstrapRootSnapshot();
            }

            @Override
            public void close() {
                value.close();
            }
        };
    }
}
