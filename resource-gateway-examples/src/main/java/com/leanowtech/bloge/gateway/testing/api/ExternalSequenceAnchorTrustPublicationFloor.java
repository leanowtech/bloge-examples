package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Durable monotonic floor for managed external sequence-anchor trust publications.
 *
 * <p>The floor is advanced only after canonical binding, freshness and bootstrap-root signature
 * verification. A missing floor accepts only genesis; an existing floor accepts only the exact
 * current publication or its contiguous exact successor.</p>
 */
public interface ExternalSequenceAnchorTrustPublicationFloor {

    /** Persists or verifies one exact managed notary trust generation. */
    void accept(Generation generation);

    /** @return whether state survives process and application database restart */
    boolean durable();

    /**
     * Reuses the mature whole-record-fingerprinted serving-inventory trust-root floor contract.
     *
     * <p>The caller must construct the delegate with the same scope and trust-root-set identity.
     * A dedicated interface prevents accidental Spring injection into serving-inventory roots.</p>
     *
     * @param delegate durable monotonic floor implementation
     * @return type-safe managed-notary publication floor
     */
    static ExternalSequenceAnchorTrustPublicationFloor adapt(
            TestSuiteStabilityServingInventoryTrustRootFloor delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new ExternalSequenceAnchorTrustPublicationFloor() {
            @Override
            public void accept(Generation generation) {
                Objects.requireNonNull(generation, "generation");
                delegate.accept(new TestSuiteStabilityServingInventoryTrustRootFloor.Generation(
                        TestSuiteStabilityServingInventoryTrustRootFloor.Generation.SCHEMA_VERSION,
                        generation.scopeId(), generation.trustRootSetId(), generation.sequence(),
                        generation.materialFingerprint(),
                        generation.previousMaterialFingerprint()));
            }

            @Override
            public boolean durable() {
                return delegate.durable();
            }
        };
    }

    /** @return fail-closed non-durable floor for profiles without managed notary trust */
    static ExternalSequenceAnchorTrustPublicationFloor unavailable() {
        return new ExternalSequenceAnchorTrustPublicationFloor() {
            @Override
            public void accept(Generation generation) {
                Objects.requireNonNull(generation, "generation");
                throw new IllegalStateException(
                        "Managed external sequence-anchor trust floor is unavailable");
            }

            @Override
            public boolean durable() {
                return false;
            }
        };
    }

    /**
     * Exact publication generation submitted to the durable floor.
     *
     * @param schemaVersion floor protocol generation
     * @param scopeId stable fleet scope
     * @param trustRootSetId stable managed notary trust-set identity
     * @param sequence contiguous one-based generation
     * @param materialFingerprint exact current publication identity
     * @param previousMaterialFingerprint exact predecessor, blank only at genesis
     */
    record Generation(
            String schemaVersion,
            String scopeId,
            String trustRootSetId,
            long sequence,
            String materialFingerprint,
            String previousMaterialFingerprint) {

        /** Current managed notary floor generation protocol. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorTrustGeneration.v1";

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Enforces canonical genesis or exact-successor shape. */
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
                    || sequence < 1 || !FINGERPRINT.matcher(materialFingerprint).matches()
                    || !predecessorShape) {
                throw new IllegalArgumentException(
                        "Invalid managed external sequence-anchor trust generation");
            }
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
