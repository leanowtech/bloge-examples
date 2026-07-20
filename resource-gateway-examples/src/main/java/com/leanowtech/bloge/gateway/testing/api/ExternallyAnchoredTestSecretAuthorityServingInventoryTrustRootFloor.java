package com.leanowtech.bloge.gateway.testing.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * External-first non-equivocation wrapper for test-secret managed runtime-key heads.
 *
 * <p>The signed root material fingerprint already commits both deployment and witness runtime key
 * sets. A namespaced stream id prevents collision with suite-stability roots even if deployments
 * reuse the same scope and human-readable root-set id.</p>
 */
public final class ExternallyAnchoredTestSecretAuthorityServingInventoryTrustRootFloor
        implements TestSecretAuthorityServingInventoryTrustRootFloor {

    private static final String STREAM_NAMESPACE =
            "bloge.testSecretAuthorityServingInventoryExternalTrustRootStream.v1";
    private static final String STREAM_PREFIX = "test-secret-root-";

    private final TestSecretAuthorityServingInventoryTrustRootFloor delegate;
    private final TestSecretAuthorityExternalSequenceAnchor externalAnchor;

    /**
     * Creates an external-first wrapper over one local durable managed-root floor.
     *
     * @param delegate local durable managed-root floor
     * @param externalAnchor independently durable compare-and-append quorum
     */
    public ExternallyAnchoredTestSecretAuthorityServingInventoryTrustRootFloor(
            TestSecretAuthorityServingInventoryTrustRootFloor delegate,
            TestSecretAuthorityExternalSequenceAnchor externalAnchor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.externalAnchor = requireExternal(externalAnchor);
        if (!delegate.durable()) {
            throw new IllegalArgumentException(
                    "External test-secret trust-root anchor requires a durable local floor");
        }
    }

    /** Anchors the exact atomic dual runtime-key material before local advancement. */
    @Override
    public void accept(Generation generation) {
        Objects.requireNonNull(generation, "generation");
        externalAnchor.accept(new TestSuiteStabilityExternalSequenceAnchor.Head(
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
        return externalAnchor.descriptor().byzantineQuorum();
    }

    /** Derives one bounded, product-domain-separated external stream identity. */
    static String streamId(String trustRootSetId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (STREAM_NAMESPACE + '\0' + Objects.requireNonNull(trustRootSetId,
                            "trustRootSetId")).getBytes(StandardCharsets.UTF_8));
            return STREAM_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static TestSecretAuthorityExternalSequenceAnchor requireExternal(
            TestSecretAuthorityExternalSequenceAnchor anchor) {
        TestSecretAuthorityExternalSequenceAnchor value = Objects.requireNonNull(
                anchor, "externalAnchor");
        TestSuiteStabilityExternalSequenceAnchor.Descriptor descriptor = value.descriptor();
        if (!descriptor.available() || !descriptor.externallyDurable()
                || !descriptor.challengeBound()) {
            throw new IllegalArgumentException(
                    "External test-secret trust-root anchor is unavailable or unsafe");
        }
        return value;
    }
}
