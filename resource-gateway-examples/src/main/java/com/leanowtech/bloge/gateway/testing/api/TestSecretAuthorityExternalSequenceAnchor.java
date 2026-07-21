package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;

/**
 * Domain-isolated external non-equivocation port for test-secret inventory chains.
 *
 * <p>The wire protocol deliberately reuses the stable external sequence v1 head, receipt, and
 * quorum semantics already used by suite stability. Product-domain isolation is carried by the
 * wrappers' stable stream identifiers rather than by extending the protocol's closed v1 enum.
 * This distinct Java type prevents Spring from accidentally injecting a suite-stability notary
 * configured under another trust policy.</p>
 */
public interface TestSecretAuthorityExternalSequenceAnchor extends AutoCloseable {

    /** Anchors one exact test-secret sequence head before local durable state may advance. */
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

    /** Static or unavailable implementations own no refresh resources. */
    @Override
    default void close() {
    }

    /**
     * Adapts the shared strict HTTP/quorum implementation behind the domain-isolated port.
     *
     * @param delegate externally durable challenge-bound sequence authority
     * @return test-secret-scoped adapter
     */
    static TestSecretAuthorityExternalSequenceAnchor adapt(
            TestSuiteStabilityExternalSequenceAnchor delegate) {
        TestSuiteStabilityExternalSequenceAnchor value = Objects.requireNonNull(
                delegate, "delegate");
        return new TestSecretAuthorityExternalSequenceAnchor() {
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
            public ExternalSequenceAnchorBootstrapRootTrustStore.Snapshot bootstrapRootSnapshot() {
                return value.bootstrapRootSnapshot();
            }

            @Override
            public void close() {
                value.close();
            }
        };
    }
}
