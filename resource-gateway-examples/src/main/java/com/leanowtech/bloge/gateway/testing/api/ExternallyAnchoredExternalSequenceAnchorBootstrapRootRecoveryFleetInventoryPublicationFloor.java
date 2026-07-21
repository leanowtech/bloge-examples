package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Map;
import java.util.Objects;

/**
 * External-first non-equivocation wrapper for recovery-fleet publication/witness heads.
 *
 * <p>The signed deployment publication and independent witness fingerprints become one canonical
 * external head. A notary therefore cannot advance either proof chain in isolation. The external
 * compare-and-append quorum commits before the local database floor; if the local commit outcome
 * is uncertain, retrying the same candidate is externally idempotent and never exposes a locally
 * accepted but externally unanchored generation.</p>
 */
public final class
        ExternallyAnchoredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
        implements ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor {

    private static final String COMPOSITE_SCHEMA =
            "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetExternalPublicationHead.v1";

    private final ObjectMapper objectMapper;
    private final ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
            delegate;
    private final ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor
            externalAnchor;

    /**
     * Creates an external-first wrapper over one local durable publication floor.
     *
     * @param objectMapper canonical composite-head mapper
     * @param delegate local durable recovery-fleet publication floor
     * @param externalAnchor independently durable compare-and-append quorum
     */
    public ExternallyAnchoredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor(
            ObjectMapper objectMapper,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor delegate,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor
                    externalAnchor) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.externalAnchor = requireExternal(externalAnchor);
        if (!delegate.durable()) {
            throw new IllegalArgumentException(
                    "External recovery-fleet publication anchor requires a durable local floor");
        }
    }

    /** Anchors the atomic publication/witness head before advancing local state. */
    @Override
    public void accept(Generation generation) {
        Objects.requireNonNull(generation, "generation");
        String current = composite(generation.deploymentScopeId(), generation.fleetId(),
                generation.sequence(), generation.publicationMaterialFingerprint(),
                generation.witnessMaterialFingerprint());
        String previous = generation.sequence() == 1 ? "" : composite(
                generation.deploymentScopeId(), generation.fleetId(),
                generation.sequence() - 1, generation.previousPublicationFingerprint(),
                generation.previousWitnessFingerprint());
        externalAnchor.accept(new TestSuiteStabilityExternalSequenceAnchor.Head(
                TestSuiteStabilityExternalSequenceAnchor.Head.SCHEMA_VERSION,
                TestSuiteStabilityExternalSequenceAnchor.StreamKind
                        .SERVING_INVENTORY_PUBLICATION,
                generation.deploymentScopeId(),
                ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalStreams.publication(
                        generation.fleetId()),
                generation.sequence(), current, previous));
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

    private String composite(
            String deploymentScopeId,
            String fleetId,
            long sequence,
            String publicationFingerprint,
            String witnessFingerprint) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", COMPOSITE_SCHEMA,
                "deploymentScopeId", deploymentScopeId,
                "fleetId", fleetId,
                "sequence", sequence,
                "publicationMaterialFingerprint", publicationFingerprint,
                "witnessMaterialFingerprint", witnessFingerprint));
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
                    "External recovery-fleet publication anchor is unavailable or unsafe");
        }
        return value;
    }
}
