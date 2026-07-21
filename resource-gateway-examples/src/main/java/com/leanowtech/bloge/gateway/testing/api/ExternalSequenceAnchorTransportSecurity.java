package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Payload-free transport posture for one external sequence-anchor trust chain.
 *
 * <p>The notary transport is always present. Managed notary-trust and complete bootstrap-root
 * transports are present only when those remote sources are enabled. The projection deliberately
 * excludes endpoints, paths, secret references, pins, certificate identities, and key material so
 * it is safe for capability and health surfaces.</p>
 *
 * @param schemaVersion projection protocol version
 * @param notary external checkpoint-quorum transport
 * @param managedTrustPublication managed receipt-key publication transport, when configured
 * @param bootstrapRootBundle complete bootstrap-root bundle transport, when configured
 */
public record ExternalSequenceAnchorTransportSecurity(
        String schemaVersion,
        ControlPlaneHttpTransport.Descriptor notary,
        Optional<ControlPlaneHttpTransport.Descriptor> managedTrustPublication,
        Optional<ControlPlaneHttpTransport.Descriptor> bootstrapRootBundle) {

    /** Current payload-free transport-security projection generation. */
    public static final String SCHEMA_VERSION =
            "bloge.externalSequenceAnchorTransportSecurity.v1";

    /** Freezes optional values and rejects an impossible root-without-trust composition. */
    public ExternalSequenceAnchorTransportSecurity {
        schemaVersion = Objects.requireNonNullElse(schemaVersion, "").trim();
        notary = Objects.requireNonNull(notary, "notary");
        managedTrustPublication = managedTrustPublication == null
                ? Optional.empty() : managedTrustPublication;
        bootstrapRootBundle = bootstrapRootBundle == null
                ? Optional.empty() : bootstrapRootBundle;
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || bootstrapRootBundle.isPresent() && managedTrustPublication.isEmpty()) {
            throw new IllegalArgumentException(
                    "External sequence-anchor transport security is invalid");
        }
    }

    /**
     * Returns the historical system-trust posture for adapters that have not supplied transport
     * metadata yet.
     *
     * @return system-trust notary with no managed publication sources
     */
    public static ExternalSequenceAnchorTransportSecurity compatibility() {
        return new ExternalSequenceAnchorTransportSecurity(
                SCHEMA_VERSION, SystemTrustRecoveryFleetPublicationTransport.descriptorValue(),
                Optional.empty(), Optional.empty());
    }
}
