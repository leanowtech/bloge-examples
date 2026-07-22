package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Durable monotonic authority for physical provider-inventory publication chain heads.
 *
 * <p>Callers must verify the nested inventory, ACTIVE/REVOKED publication, independent witness,
 * and local deployment binding before accepting a generation. Implementations atomically reject
 * rollback, same-sequence fork, sequence gaps, and either predecessor mismatch.</p>
 */
public interface TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor {

    /**
     * Accepts an already verified publication generation before local visibility.
     *
     * @param generation complete private publication and witness chain identity
     */
    void accept(Generation generation);

    /**
     * Reports whether the floor survives process and complete fleet restart.
     *
     * @return true only for a durable implementation
     */
    boolean durable();

    /**
     * Private publication and witness chain identity.
     *
     * @param schemaVersion generation protocol version
     * @param scopeId stable physical provider-fleet scope
     * @param sequence signed publication sequence
     * @param publicationMaterialFingerprint current publication material identity
     * @param witnessMaterialFingerprint current independent witness identity
     * @param previousPublicationFingerprint previous publication, blank at sequence one
     * @param previousWitnessFingerprint previous witness, blank at sequence one
     */
    record Generation(
            String schemaVersion,
            String scopeId,
            long sequence,
            String publicationMaterialFingerprint,
            String witnessMaterialFingerprint,
            String previousPublicationFingerprint,
            String previousWitnessFingerprint) {

        /** Current physical provider-inventory floor candidate generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptProviderInventoryPublicationGeneration.v1";
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Rejects partial or predecessor-ambiguous floor candidates. */
        public Generation {
            schemaVersion = normalized(schemaVersion);
            scopeId = normalized(scopeId);
            publicationMaterialFingerprint = normalized(publicationMaterialFingerprint);
            witnessMaterialFingerprint = normalized(witnessMaterialFingerprint);
            previousPublicationFingerprint = normalized(previousPublicationFingerprint);
            previousWitnessFingerprint = normalized(previousWitnessFingerprint);
            boolean predecessorValid = sequence == 1
                    && previousPublicationFingerprint.isEmpty()
                    && previousWitnessFingerprint.isEmpty()
                    || sequence > 1
                    && FINGERPRINT.matcher(previousPublicationFingerprint).matches()
                    && FINGERPRINT.matcher(previousWitnessFingerprint).matches();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(scopeId).matches() || sequence < 1
                    || !FINGERPRINT.matcher(publicationMaterialFingerprint).matches()
                    || !FINGERPRINT.matcher(witnessMaterialFingerprint).matches()
                    || !predecessorValid) {
                throw new IllegalArgumentException(
                        "Physical provider-inventory publication floor generation is invalid");
            }
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
