package com.leanowtech.bloge.gateway.testing.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * External-first non-equivocation floor for physical provider-inventory managed runtime keys.
 *
 * <p>The signed material fingerprint commits the complete deployment and witness runtime-key
 * generation. This wrapper submits that exact head to the independently durable physical-domain
 * quorum before advancing the local database floor. If the local commit is uncertain, retrying the
 * same generation repeats the same external compare-and-append and can safely repair local state.
 * A domain-separated bounded stream id prevents collisions with inventory publications and other
 * products even when operators reuse a scope or root-set name.</p>
 */
public final class
        ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor
        implements TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor {

    private static final String STREAM_NAMESPACE =
            "bloge.testSuiteStabilityPhysicalAttemptProviderInventoryExternalTrustRootStream.v1";
    private static final String STREAM_PREFIX = "physical-provider-root-";

    private final TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor delegate;
    private final TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor anchor;

    /**
     * Creates an external-first wrapper over one durable local managed-root floor.
     *
     * @param delegate local durable managed-root floor
     * @param anchor independently durable challenge-bound physical-domain sequence quorum
     */
    public ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor(
            TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor delegate,
            TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor anchor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.anchor = requireExternal(anchor);
        if (!delegate.durable()) {
            throw new IllegalArgumentException(
                    "External physical provider-inventory trust-root anchor requires a durable local floor");
        }
    }

    /** Anchors the exact atomic dual runtime-key generation before local advancement. */
    @Override
    public void accept(Generation generation) {
        Objects.requireNonNull(generation, "generation");
        anchor.accept(new TestSuiteStabilityExternalSequenceAnchor.Head(
                TestSuiteStabilityExternalSequenceAnchor.Head.SCHEMA_VERSION,
                TestSuiteStabilityExternalSequenceAnchor.StreamKind
                        .SERVING_INVENTORY_TRUST_ROOT,
                generation.scopeId(), streamId(generation.trustRootSetId()),
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
        return anchor.descriptor().byzantineQuorum();
    }

    /**
     * Derives a fixed-size physical-product stream identity without exposing the root-set name.
     *
     * @param trustRootSetId stable managed dual-key set identity
     * @return domain-separated bounded stream identity
     */
    static String streamId(String trustRootSetId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (STREAM_NAMESPACE + '\0' + Objects.requireNonNull(
                            trustRootSetId, "trustRootSetId"))
                            .getBytes(StandardCharsets.UTF_8));
            return STREAM_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor
            requireExternal(
            TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor anchor) {
        var value = Objects.requireNonNull(anchor, "anchor");
        TestSuiteStabilityExternalSequenceAnchor.Descriptor descriptor = value.descriptor();
        if (!descriptor.available() || !descriptor.externallyDurable()
                || !descriptor.challengeBound()) {
            throw new IllegalArgumentException(
                    "External physical provider-inventory trust-root anchor is unavailable or unsafe");
        }
        return value;
    }
}
