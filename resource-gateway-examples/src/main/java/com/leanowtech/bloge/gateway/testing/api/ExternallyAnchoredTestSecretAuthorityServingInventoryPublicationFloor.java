package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Map;
import java.util.Objects;

/**
 * External-first non-equivocation wrapper for test-secret inventory publication/witness heads.
 *
 * <p>The deployment publication and independent witness fingerprints are reduced to one canonical
 * composite head. The external quorum therefore cannot accept either chain in isolation. External
 * compare-and-append completes before the local database floor; an exact retry repairs an uncertain
 * local commit without ever exposing a locally accepted, externally unanchored generation.</p>
 */
public final class ExternallyAnchoredTestSecretAuthorityServingInventoryPublicationFloor
        implements TestSecretAuthorityServingInventoryPublicationFloor {

    /** Stable stream identity within one test-secret serving-fleet scope. */
    public static final String STREAM_ID =
            "test-secret-authority-serving-inventory-publication";

    private static final String COMPOSITE_SCHEMA =
            "bloge.testSecretAuthorityServingInventoryExternalPublicationHead.v1";

    private final ObjectMapper objectMapper;
    private final TestSecretAuthorityServingInventoryPublicationFloor delegate;
    private final TestSecretAuthorityExternalSequenceAnchor externalAnchor;

    /**
     * Creates an external-first wrapper over one local durable publication floor.
     *
     * @param objectMapper canonical composite-head mapper
     * @param delegate local durable publication/witness floor
     * @param externalAnchor independently durable compare-and-append quorum
     */
    public ExternallyAnchoredTestSecretAuthorityServingInventoryPublicationFloor(
            ObjectMapper objectMapper,
            TestSecretAuthorityServingInventoryPublicationFloor delegate,
            TestSecretAuthorityExternalSequenceAnchor externalAnchor) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.externalAnchor = requireExternal(externalAnchor);
        if (!delegate.durable()) {
            throw new IllegalArgumentException(
                    "External test-secret publication anchor requires a durable local floor");
        }
    }

    /** Anchors the exact composite publication/witness head before local advancement. */
    @Override
    public void accept(Generation generation) {
        Objects.requireNonNull(generation, "generation");
        String current = composite(
                generation.scopeId(), generation.sequence(),
                generation.publicationMaterialFingerprint(),
                generation.witnessMaterialFingerprint());
        String previous = generation.sequence() == 1 ? "" : composite(
                generation.scopeId(), generation.sequence() - 1,
                generation.previousPublicationFingerprint(),
                generation.previousWitnessFingerprint());
        externalAnchor.accept(new TestSuiteStabilityExternalSequenceAnchor.Head(
                TestSuiteStabilityExternalSequenceAnchor.Head.SCHEMA_VERSION,
                TestSuiteStabilityExternalSequenceAnchor.StreamKind
                        .SERVING_INVENTORY_PUBLICATION,
                generation.scopeId(), STREAM_ID, generation.sequence(), current, previous));
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
            String scopeId,
            long sequence,
            String publicationFingerprint,
            String witnessFingerprint) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", COMPOSITE_SCHEMA,
                "scopeId", scopeId,
                "sequence", sequence,
                "publicationMaterialFingerprint", publicationFingerprint,
                "witnessMaterialFingerprint", witnessFingerprint));
    }

    private static TestSecretAuthorityExternalSequenceAnchor requireExternal(
            TestSecretAuthorityExternalSequenceAnchor anchor) {
        TestSecretAuthorityExternalSequenceAnchor value = Objects.requireNonNull(
                anchor, "externalAnchor");
        TestSuiteStabilityExternalSequenceAnchor.Descriptor descriptor = value.descriptor();
        if (!descriptor.available() || !descriptor.externallyDurable()
                || !descriptor.challengeBound()) {
            throw new IllegalArgumentException(
                    "External test-secret publication anchor is unavailable or unsafe");
        }
        return value;
    }
}
