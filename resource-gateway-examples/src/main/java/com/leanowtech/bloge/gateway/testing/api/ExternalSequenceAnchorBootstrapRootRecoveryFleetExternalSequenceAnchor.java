package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;

/**
 * Domain-isolated external non-equivocation port for recovery-fleet inventory chains.
 *
 * <p>The strict HTTP/quorum implementation and its v1 checkpoint wire are intentionally shared
 * with the mature serving-inventory path. The legacy {@code SERVING_INVENTORY_*} stream kinds are
 * ordering classes only; product, deployment, fleet, and root-set isolation is carried by the
 * wrappers' deterministic stream identities. This distinct Java type prevents Spring from
 * injecting a suite-stability or test-secret notary that belongs to another trust policy.</p>
 */
public interface ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor
        extends AutoCloseable {

    /** Anchors one exact recovery-fleet sequence head before local state may advance. */
    void accept(TestSuiteStabilityExternalSequenceAnchor.Head head);

    /** @return aggregate key-free configuration descriptor */
    TestSuiteStabilityExternalSequenceAnchor.Descriptor descriptor();

    /** @return aggregate runtime state; this read must not perform remote I/O */
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

    /** Caller-owned or unavailable implementations own no refresh resources by default. */
    @Override
    default void close() {
    }

    /**
     * Adapts the shared strict HTTP/quorum implementation behind this domain-isolated port.
     *
     * @param delegate externally durable challenge-bound sequence authority
     * @return recovery-fleet-scoped adapter that owns the delegate lifecycle
     */
    static ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor adapt(
            TestSuiteStabilityExternalSequenceAnchor delegate) {
        TestSuiteStabilityExternalSequenceAnchor value = Objects.requireNonNull(
                delegate, "delegate");
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor() {
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
