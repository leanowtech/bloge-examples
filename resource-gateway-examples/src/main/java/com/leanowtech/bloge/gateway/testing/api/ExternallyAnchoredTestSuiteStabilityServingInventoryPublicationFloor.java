package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Map;
import java.util.Objects;

/**
 * External-first non-equivocation wrapper for serving-inventory publication/witness heads.
 *
 * <p>The two independent chain fingerprints are projected into one deterministic head so a notary
 * cannot authorize a publication advance without its exact witness advance. External
 * compare-and-append completes before the local database floor. If the local commit then fails,
 * retrying the same candidate is externally idempotent; the reverse ordering would leave a local
 * generation that has never been externally anchored.</p>
 */
public final class ExternallyAnchoredTestSuiteStabilityServingInventoryPublicationFloor
        implements TestSuiteStabilityServingInventoryPublicationFloor {

    /** Stable stream identity within each serving fleet scope. */
    public static final String STREAM_ID = "suite-stability-serving-inventory-publication";
    private static final String COMPOSITE_SCHEMA =
            "bloge.testSuiteStabilityServingInventoryExternalPublicationHead.v1";

    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityServingInventoryPublicationFloor delegate;
    private final TestSuiteStabilityExternalSequenceAnchor externalAnchor;

    /**
     * Creates an external-first wrapper over one local database floor.
     *
     * @param objectMapper canonical composite-head mapper
     * @param delegate local durable publication floor
     * @param externalAnchor independently durable compare-and-append quorum
     */
    public ExternallyAnchoredTestSuiteStabilityServingInventoryPublicationFloor(
            ObjectMapper objectMapper,
            TestSuiteStabilityServingInventoryPublicationFloor delegate,
            TestSuiteStabilityExternalSequenceAnchor externalAnchor) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.externalAnchor = requireExternal(externalAnchor);
        if (!delegate.durable()) {
            throw new IllegalArgumentException(
                    "External publication anchor requires a durable local floor");
        }
    }

    /** Anchors the atomic publication/witness head before advancing local state. */
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

    private static TestSuiteStabilityExternalSequenceAnchor requireExternal(
            TestSuiteStabilityExternalSequenceAnchor anchor) {
        TestSuiteStabilityExternalSequenceAnchor value = Objects.requireNonNull(
                anchor, "externalAnchor");
        TestSuiteStabilityExternalSequenceAnchor.Descriptor descriptor = value.descriptor();
        if (!descriptor.available() || !descriptor.externallyDurable()
                || !descriptor.challengeBound()) {
            throw new IllegalArgumentException(
                    "External publication anchor is unavailable or unsafe");
        }
        return value;
    }
}
