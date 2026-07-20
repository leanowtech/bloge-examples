package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Durable monotonic authority for verified test-secret inventory publication generations.
 *
 * <p>The caller verifies the publication, independent witness, nested inventory and local
 * authority binding before presenting a candidate. Implementations atomically reject rollback,
 * same-sequence fork, sequence gaps and either predecessor mismatch.</p>
 */
public interface TestSecretAuthorityServingInventoryPublicationFloor {

    /** @param generation complete verified private chain identity to accept atomically */
    void accept(Generation generation);

    /** @return true only when the floor survives process and complete fleet restart */
    boolean durable();

    /** @return true only when every accepted head is first committed outside the local database */
    default boolean externallyAnchored() {
        return false;
    }

    /** @return true only when the external anchor declares an intersecting Byzantine quorum */
    default boolean byzantineQuorumAnchored() {
        return false;
    }

    /**
     * Private publication and witness chain identity.
     *
     * @param schemaVersion generation protocol version
     * @param scopeId stable test-secret serving-fleet scope
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

        /** Current test-secret publication floor-candidate protocol generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSecretAuthorityServingInventoryPublicationGeneration.v1";
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
                        "Invalid test-secret inventory publication floor generation");
            }
        }

        private static String normalized(String value) {
            return Objects.requireNonNullElse(value, "").trim();
        }
    }
}
