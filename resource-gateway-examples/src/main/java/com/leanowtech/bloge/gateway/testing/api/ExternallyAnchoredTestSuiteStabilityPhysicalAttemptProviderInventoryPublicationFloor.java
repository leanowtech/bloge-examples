package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Map;
import java.util.Objects;

/**
 * External-first non-equivocation floor for physical provider-inventory generations.
 *
 * <p>The publication and independent witness fingerprints are reduced to one canonical composite
 * head. The external quorum therefore cannot accept either mutable chain in isolation. External
 * compare-and-append completes before the local database floor; if the local commit is uncertain,
 * retrying the same generation is externally idempotent and repairs local state without exposing
 * an externally unanchored local generation.</p>
 */
public final class
        ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor
        implements TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor {

    /** Stable product-domain stream identity within each physical provider fleet scope. */
    public static final String STREAM_ID =
            "physical-attempt-provider-inventory-publication";

    private static final String COMPOSITE_SCHEMA =
            "bloge.testSuiteStabilityPhysicalAttemptProviderInventoryExternalHead.v1";

    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor delegate;
    private final TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor anchor;

    /**
     * Creates an external-first wrapper over one durable local floor.
     *
     * @param objectMapper canonical composite-head mapper
     * @param delegate local durable publication/witness floor
     * @param anchor independently durable challenge-bound compare-and-append quorum
     */
    public ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor(
            ObjectMapper objectMapper,
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor delegate,
            TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor anchor) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.anchor = requireExternal(anchor);
        if (!delegate.durable()) {
            throw new IllegalArgumentException(
                    "External physical provider-inventory anchor requires a durable local floor");
        }
    }

    /** Anchors the exact composite generation before advancing local durable state. */
    @Override
    public void accept(Generation generation) {
        Objects.requireNonNull(generation, "generation");
        String current = composite(generation.scopeId(), generation.sequence(),
                generation.publicationMaterialFingerprint(),
                generation.witnessMaterialFingerprint());
        String previous = generation.sequence() == 1 ? "" : composite(
                generation.scopeId(), generation.sequence() - 1,
                generation.previousPublicationFingerprint(),
                generation.previousWitnessFingerprint());
        anchor.accept(new TestSuiteStabilityExternalSequenceAnchor.Head(
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
        return anchor.descriptor().byzantineQuorum();
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

    private static TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor
            requireExternal(
            TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor anchor) {
        var value = Objects.requireNonNull(anchor, "anchor");
        TestSuiteStabilityExternalSequenceAnchor.Descriptor descriptor = value.descriptor();
        if (!descriptor.available() || !descriptor.externallyDurable()
                || !descriptor.challengeBound()) {
            throw new IllegalArgumentException(
                    "External physical provider-inventory anchor is unavailable or unsafe");
        }
        return value;
    }
}
