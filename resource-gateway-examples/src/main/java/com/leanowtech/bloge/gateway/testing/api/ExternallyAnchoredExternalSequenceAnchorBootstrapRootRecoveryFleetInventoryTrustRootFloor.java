package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;

/**
 * External-first non-equivocation wrapper for recovery-fleet atomic dual-root heads.
 *
 * <p>The signed root material fingerprint already commits both deployment and witness runtime key
 * sets. A product-, fleet-, and root-set-separated stream is externally committed before the local
 * database floor, so an uncertain local outcome can be retried without publishing an unanchored
 * runtime-key generation.</p>
 */
public final class
        ExternallyAnchoredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor
        implements ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor {

    private final ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor delegate;
    private final ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor
            externalAnchor;

    /**
     * Creates an external-first wrapper over one local durable trust-root floor.
     *
     * @param delegate local durable recovery-fleet dual-root floor
     * @param externalAnchor independently durable compare-and-append quorum
     */
    public ExternallyAnchoredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor delegate,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor
                    externalAnchor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.externalAnchor = requireExternal(externalAnchor);
        if (!delegate.durable()) {
            throw new IllegalArgumentException(
                    "External recovery-fleet trust-root anchor requires a durable local floor");
        }
    }

    /** Anchors the exact atomic dual-root material before advancing local state. */
    @Override
    public void accept(Generation generation) {
        Objects.requireNonNull(generation, "generation");
        externalAnchor.accept(new TestSuiteStabilityExternalSequenceAnchor.Head(
                TestSuiteStabilityExternalSequenceAnchor.Head.SCHEMA_VERSION,
                TestSuiteStabilityExternalSequenceAnchor.StreamKind
                        .SERVING_INVENTORY_TRUST_ROOT,
                generation.deploymentScopeId(),
                ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalStreams.trustRoot(
                        generation.fleetId(), generation.trustRootSetId()),
                generation.sequence(), generation.materialFingerprint(),
                generation.previousMaterialFingerprint()));
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

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor
            requireExternal(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor anchor) {
        ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor value =
                Objects.requireNonNull(anchor, "externalAnchor");
        TestSuiteStabilityExternalSequenceAnchor.Descriptor descriptor = value.descriptor();
        if (!descriptor.available() || !descriptor.externallyDurable()
                || !descriptor.challengeBound()) {
            throw new IllegalArgumentException(
                    "External recovery-fleet trust-root anchor is unavailable or unsafe");
        }
        return value;
    }
}
