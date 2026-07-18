package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;

/**
 * External-first non-equivocation wrapper for managed serving-inventory runtime-key heads.
 *
 * <p>The root publication material fingerprint is already the canonical identity of both runtime
 * key sets, so it maps directly to the generic external stream. The external quorum commits before
 * the local database floor and local publication.</p>
 */
public final class ExternallyAnchoredTestSuiteStabilityServingInventoryTrustRootFloor
        implements TestSuiteStabilityServingInventoryTrustRootFloor {

    private final TestSuiteStabilityServingInventoryTrustRootFloor delegate;
    private final TestSuiteStabilityExternalSequenceAnchor externalAnchor;

    /**
     * Creates an external-first wrapper over one local database floor.
     *
     * @param delegate local durable managed-root floor
     * @param externalAnchor independently durable compare-and-append quorum
     */
    public ExternallyAnchoredTestSuiteStabilityServingInventoryTrustRootFloor(
            TestSuiteStabilityServingInventoryTrustRootFloor delegate,
            TestSuiteStabilityExternalSequenceAnchor externalAnchor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.externalAnchor = requireExternal(externalAnchor);
        if (!delegate.durable()) {
            throw new IllegalArgumentException(
                    "External trust-root anchor requires a durable local floor");
        }
    }

    /** Anchors the exact managed-root material before advancing local state. */
    @Override
    public void accept(Generation generation) {
        Objects.requireNonNull(generation, "generation");
        externalAnchor.accept(new TestSuiteStabilityExternalSequenceAnchor.Head(
                TestSuiteStabilityExternalSequenceAnchor.Head.SCHEMA_VERSION,
                TestSuiteStabilityExternalSequenceAnchor.StreamKind
                        .SERVING_INVENTORY_TRUST_ROOT,
                generation.scopeId(), generation.trustRootSetId(), generation.sequence(),
                generation.materialFingerprint(), generation.previousMaterialFingerprint()));
        delegate.accept(generation);
    }

    /** {@inheritDoc} */
    @Override
    public boolean durable() {
        return delegate.durable();
    }

    /** {@inheritDoc} */
    @Override
    public boolean externallyAnchored() {
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean byzantineQuorumAnchored() {
        return externalAnchor.descriptor().byzantineQuorum();
    }

    private static TestSuiteStabilityExternalSequenceAnchor requireExternal(
            TestSuiteStabilityExternalSequenceAnchor anchor) {
        TestSuiteStabilityExternalSequenceAnchor value = Objects.requireNonNull(
                anchor, "externalAnchor");
        TestSuiteStabilityExternalSequenceAnchor.Descriptor descriptor = value.descriptor();
        if (!descriptor.available() || !descriptor.externallyDurable()
                || !descriptor.challengeBound()) {
            throw new IllegalArgumentException(
                    "External trust-root anchor is unavailable or unsafe");
        }
        return value;
    }
}
