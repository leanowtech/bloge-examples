package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Durable monotonic authority for verified recovery-fleet dual trust-root generations.
 *
 * <p>Callers verify both bootstrap-root quorums, canonical material, current validity, exact local
 * binding, and process-local predecessor continuity before invoking this interface. Adapters must
 * atomically reject rollback, same-sequence fork, gap, and predecessor mismatch.</p>
 */
public interface ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor {

    /**
     * Accepts one fully verified generation before its runtime keys become locally observable.
     *
     * @param generation exact verified dual-root generation
     */
    void accept(Generation generation);

    /**
     * Reports whether accepted generations survive process and complete fleet restart.
     *
     * @return true only when the floor is durable
     */
    boolean durable();

    /**
     * Reports whether acceptance is ordered by a non-rollbackable external authority.
     *
     * @return true only when every accepted head is first committed outside the rollbackable
     * Resource Gateway database
     */
    default boolean externallyAnchored() {
        return false;
    }

    /**
     * Reports whether the external authority has an intersecting Byzantine quorum.
     *
     * @return true only when the stronger distributed ordering property is present
     */
    default boolean byzantineQuorumAnchored() {
        return false;
    }

    /**
     * Exact private identity submitted to the durable floor.
     *
     * @param schemaVersion floor-candidate protocol generation
     * @param deploymentScopeId stable tenant and environment deployment scope
     * @param fleetId stable recovery-fleet identity
     * @param trustRootSetId stable managed dual key-set identity
     * @param sequence signed publication sequence
     * @param materialFingerprint current signed material identity
     * @param previousMaterialFingerprint exact predecessor, blank at sequence one
     */
    record Generation(
            String schemaVersion,
            String deploymentScopeId,
            String fleetId,
            String trustRootSetId,
            long sequence,
            String materialFingerprint,
            String previousMaterialFingerprint) {

        /** Current recovery-fleet trust-root durable-floor candidate generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootGeneration.v1";

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Enforces canonical identity and unambiguous genesis/successor shape. */
        public Generation {
            schemaVersion = normalized(schemaVersion);
            deploymentScopeId = normalized(deploymentScopeId);
            fleetId = normalized(fleetId);
            trustRootSetId = normalized(trustRootSetId);
            materialFingerprint = normalized(materialFingerprint);
            previousMaterialFingerprint = normalized(previousMaterialFingerprint);
            boolean predecessorShape = sequence == 1 && previousMaterialFingerprint.isEmpty()
                    || sequence > 1
                    && FINGERPRINT.matcher(previousMaterialFingerprint).matches();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(deploymentScopeId).matches()
                    || !IDENTIFIER.matcher(fleetId).matches()
                    || !IDENTIFIER.matcher(trustRootSetId).matches()
                    || sequence < 1
                    || !FINGERPRINT.matcher(materialFingerprint).matches()
                    || !predecessorShape) {
                throw new IllegalArgumentException(
                        "Invalid recovery-fleet inventory trust-root floor generation");
            }
        }

        private static String normalized(String value) {
            return Objects.requireNonNullElse(value, "").trim();
        }
    }
}
