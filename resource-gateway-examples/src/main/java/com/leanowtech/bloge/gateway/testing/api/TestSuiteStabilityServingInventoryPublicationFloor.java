package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Durable monotonic authority for verified serving-inventory publication generations.
 *
 * <p>The caller must cryptographically verify the publication, witness, nested inventory, and
 * local deployment binding before invoking this authority. Implementations atomically reject
 * rollback, same-sequence fork, sequence gap, and either predecessor mismatch. No implementation
 * may silently replace or reset an existing stable-scope floor.</p>
 */
public interface TestSuiteStabilityServingInventoryPublicationFloor {

    /**
     * Accepts one verified generation or throws before it becomes locally observable.
     *
     * @param generation complete private chain identity
     */
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
     * Private chain identity presented to the durable floor.
     *
     * @param schemaVersion generation protocol version
     * @param scopeId stable serving-fleet scope
     * @param sequence signed publication sequence
     * @param publicationMaterialFingerprint current publication material identity
     * @param witnessMaterialFingerprint current independent witness identity
     * @param previousPublicationFingerprint previous publication identity, blank at sequence one
     * @param previousWitnessFingerprint previous witness identity, blank at sequence one
     */
    record Generation(
            String schemaVersion,
            String scopeId,
            long sequence,
            String publicationMaterialFingerprint,
            String witnessMaterialFingerprint,
            String previousPublicationFingerprint,
            String previousWitnessFingerprint) {

        /** Current floor-candidate protocol generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityServingInventoryPublicationGeneration.v1";

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Rejects partial, non-canonical, or sequence/predecessor-ambiguous candidates. */
        public Generation {
            schemaVersion = normalized(schemaVersion);
            scopeId = normalized(scopeId);
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
                    || !IDENTIFIER.matcher(scopeId).matches()
                    || sequence < 1
                    || !FINGERPRINT.matcher(publicationMaterialFingerprint).matches()
                    || !FINGERPRINT.matcher(witnessMaterialFingerprint).matches()
                    || !predecessorShape) {
                throw new IllegalArgumentException(
                        "Invalid serving-inventory publication floor generation");
            }
        }

        private static String normalized(String value) {
            return Objects.requireNonNullElse(value, "").trim();
        }
    }
}
