package com.leanowtech.bloge.gateway.testing.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Deterministic product-domain separation for private recovery-fleet notary streams. */
final class ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalStreams {

    private static final String PUBLICATION_NAMESPACE =
            "bloge.bootstrapRootRecoveryFleetExternalInventoryPublicationStream.v1";
    private static final String TRUST_ROOT_NAMESPACE =
            "bloge.bootstrapRootRecoveryFleetExternalInventoryTrustRootStream.v1";

    private ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalStreams() {
    }

    /** Returns one bounded stream id for a fleet's publication/witness chain. */
    static String publication(String fleetId) {
        return "recovery-fleet-publication-" + digest(PUBLICATION_NAMESPACE, fleetId);
    }

    /** Returns one bounded stream id for a fleet's atomic dual-root chain. */
    static String trustRoot(String fleetId, String trustRootSetId) {
        return "recovery-fleet-trust-root-"
                + digest(TRUST_ROOT_NAMESPACE, fleetId, trustRootSetId);
    }

    private static String digest(String namespace, String... components) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Objects.requireNonNull(namespace, "namespace")
                    .getBytes(StandardCharsets.UTF_8));
            for (String component : components) {
                digest.update((byte) 0);
                digest.update(Objects.requireNonNull(component, "component")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
