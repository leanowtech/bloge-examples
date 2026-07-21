package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Durable monotonic authority for verified recovery-fleet inventory publications.
 *
 * <p>Callers must verify the nested inventory, publication, independent witness, local runtime
 * binding, and current validity windows before invoking this authority. Implementations atomically
 * reject rollback, same-sequence fork, sequence gap, predecessor mismatch, and cross-fleet reuse.
 * Existing floors must never be silently reset or replaced.</p>
 */
public interface ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor {

    /**
     * Accepts one completely verified chain generation before local publication.
     *
     * @param generation private exact chain identity
     */
    void accept(Generation generation);

    /**
     * Reports whether accepted generations survive every process restart.
     *
     * @return true only when the floor survives process and complete fleet restart
     */
    boolean durable();

    /**
     * Reports whether an authority outside the Resource Gateway database commits first.
     *
     * @return true only when each accepted head is committed outside the rollbackable Resource
     * Gateway database before local use
     */
    default boolean externallyAnchored() {
        return false;
    }

    /**
     * Reports whether the external anchor resists the configured Byzantine fault model.
     *
     * @return true only when an external anchor proves an intersecting Byzantine quorum
     */
    default boolean byzantineQuorumAnchored() {
        return false;
    }

    /**
     * Exact private chain identity presented to the durable floor.
     *
     * @param schemaVersion generation protocol version
     * @param deploymentScopeId stable deployment scope
     * @param fleetId stable durable recovery fleet identity
     * @param sequence signed publication sequence
     * @param publicationMaterialFingerprint current publication identity
     * @param witnessMaterialFingerprint current independent witness identity
     * @param previousPublicationFingerprint previous publication, blank at sequence one
     * @param previousWitnessFingerprint previous witness, blank at sequence one
     */
    record Generation(
            String schemaVersion,
            String deploymentScopeId,
            String fleetId,
            long sequence,
            String publicationMaterialFingerprint,
            String witnessMaterialFingerprint,
            String previousPublicationFingerprint,
            String previousWitnessFingerprint) {

        /** Current floor-candidate protocol generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationGeneration.v1";

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Rejects partial, non-canonical, or sequence/predecessor-ambiguous candidates. */
        public Generation {
            schemaVersion = normalized(schemaVersion);
            deploymentScopeId = normalized(deploymentScopeId);
            fleetId = normalized(fleetId);
            publicationMaterialFingerprint = normalized(publicationMaterialFingerprint);
            witnessMaterialFingerprint = normalized(witnessMaterialFingerprint);
            previousPublicationFingerprint = normalized(previousPublicationFingerprint);
            previousWitnessFingerprint = normalized(previousWitnessFingerprint);
            boolean predecessorShape = sequence == 1
                    && previousPublicationFingerprint.isEmpty()
                    && previousWitnessFingerprint.isEmpty()
                    || sequence > 1
                    && FINGERPRINT.matcher(previousPublicationFingerprint).matches()
                    && FINGERPRINT.matcher(previousWitnessFingerprint).matches();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(deploymentScopeId).matches()
                    || !IDENTIFIER.matcher(fleetId).matches()
                    || sequence < 1
                    || !FINGERPRINT.matcher(publicationMaterialFingerprint).matches()
                    || !FINGERPRINT.matcher(witnessMaterialFingerprint).matches()
                    || !predecessorShape) {
                throw new IllegalArgumentException(
                        "Invalid recovery-fleet inventory publication floor generation");
            }
        }

        private static String normalized(String value) {
            return Objects.requireNonNullElse(value, "").trim();
        }
    }
}
