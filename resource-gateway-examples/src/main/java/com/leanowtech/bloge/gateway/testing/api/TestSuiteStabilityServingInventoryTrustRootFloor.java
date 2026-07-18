package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Durable monotonic authority for verified dual trust-root publication generations.
 *
 * <p>The caller must verify both independent bootstrap-root quorums, canonical material, current
 * validity, local binding, and process-local predecessor continuity before invoking this floor.
 * Implementations atomically reject rollback, fork, gap, and predecessor mismatch.</p>
 */
public interface TestSuiteStabilityServingInventoryTrustRootFloor {

    /** Accepts one fully verified generation before it becomes locally observable. */
    void accept(Generation generation);

    /** @return true only when the floor survives process and complete fleet restart */
    boolean durable();

    /**
     * @return true only when every accepted head is first committed outside the rollbackable
     * Resource Gateway database
     */
    default boolean externallyAnchored() {
        return false;
    }

    /** @return true only when the external anchor declares an intersecting Byzantine quorum */
    default boolean byzantineQuorumAnchored() {
        return false;
    }

    /**
     * Exact private identity submitted to the durable floor.
     *
     * @param schemaVersion floor-candidate protocol generation
     * @param scopeId stable fleet scope
     * @param trustRootSetId stable managed dual key-set identity
     * @param sequence signed publication sequence
     * @param materialFingerprint current signed material identity
     * @param previousMaterialFingerprint exact predecessor, blank at sequence one
     */
    record Generation(
            String schemaVersion,
            String scopeId,
            String trustRootSetId,
            long sequence,
            String materialFingerprint,
            String previousMaterialFingerprint) {

        /** Current private durable-floor candidate generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityServingInventoryTrustRootGeneration.v1";

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Enforces canonical identity and unambiguous genesis/successor shape. */
        public Generation {
            schemaVersion = normalized(schemaVersion);
            scopeId = normalized(scopeId);
            trustRootSetId = normalized(trustRootSetId);
            materialFingerprint = normalized(materialFingerprint);
            previousMaterialFingerprint = normalized(previousMaterialFingerprint);
            boolean predecessorShape = sequence == 1 && previousMaterialFingerprint.isEmpty()
                    || sequence > 1
                    && FINGERPRINT.matcher(previousMaterialFingerprint).matches();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(scopeId).matches()
                    || !IDENTIFIER.matcher(trustRootSetId).matches()
                    || sequence < 1
                    || !FINGERPRINT.matcher(materialFingerprint).matches()
                    || !predecessorShape) {
                throw new IllegalArgumentException(
                        "Invalid serving-inventory trust-root floor generation");
            }
        }

        private static String normalized(String value) {
            return Objects.requireNonNullElse(value, "").trim();
        }
    }
}
