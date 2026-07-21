package com.leanowtech.bloge.gateway.testing.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
    private static final Set<String> PROJECTION_FIELDS = Set.of(
            "schemaVersion", "notary", "managedTrustPublicationConfigured",
            "managedTrustPublication", "bootstrapRootBundleConfigured",
            "bootstrapRootBundle");
    private static final Set<String> DESCRIPTOR_FIELDS = Set.of(
            "systemTrustStore", "privateTrustStore", "serverSpkiPinned", "mutualTls");

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

    /**
     * Projects aggregate transport posture for descriptors and health surfaces.
     *
     * @return immutable booleans without endpoints, identities, pins, paths, or secret references
     */
    public Map<String, Object> asMap() {
        return Map.of(
                "schemaVersion", schemaVersion,
                "notary", descriptor(notary),
                "managedTrustPublicationConfigured", managedTrustPublication.isPresent(),
                "managedTrustPublication", managedTrustPublication
                        .map(ExternalSequenceAnchorTransportSecurity::descriptor)
                        .orElseGet(Map::of),
                "bootstrapRootBundleConfigured", bootstrapRootBundle.isPresent(),
                "bootstrapRootBundle", bootstrapRootBundle
                        .map(ExternalSequenceAnchorTransportSecurity::descriptor)
                        .orElseGet(Map::of));
    }

    private static Map<String, Boolean> descriptor(
            ControlPlaneHttpTransport.Descriptor value) {
        return Map.of(
                "systemTrustStore", value.systemTrustStore(),
                "privateTrustStore", value.privateTrustStore(),
                "serverSpkiPinned", value.serverSpkiPinned(),
                "mutualTls", value.mutualTls());
    }

    static boolean isValidProjection(Object value) {
        if (!(value instanceof Map<?, ?> projection)
                || !projection.keySet().equals(PROJECTION_FIELDS)
                || !SCHEMA_VERSION.equals(projection.get("schemaVersion"))
                || !isDescriptor(projection.get("notary"))) {
            return false;
        }
        return optionalDescriptor(projection, "managedTrustPublicationConfigured",
                "managedTrustPublication")
                && optionalDescriptor(projection, "bootstrapRootBundleConfigured",
                "bootstrapRootBundle");
    }

    private static boolean optionalDescriptor(
            Map<?, ?> projection,
            String configuredField,
            String descriptorField) {
        Object configured = projection.get(configuredField);
        Object descriptor = projection.get(descriptorField);
        return configured instanceof Boolean present
                && descriptor instanceof Map<?, ?> nested
                && (present ? isDescriptor(nested) : nested.isEmpty());
    }

    private static boolean isDescriptor(Object value) {
        return value instanceof Map<?, ?> descriptor
                && descriptor.keySet().equals(DESCRIPTOR_FIELDS)
                && descriptor.values().stream().allMatch(Boolean.class::isInstance);
    }
}
