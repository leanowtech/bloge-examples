package com.leanowtech.bloge.gateway.testing.api;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Durable monotonic floor for verified external-anchor bootstrap-root transition heads.
 *
 * <p>Only a fully replayed chain head may advance this floor. An empty floor accepts any head whose
 * complete genesis history was verified by the caller, allowing a new replica to join after many
 * rotations. An existing floor advances only when its exact current head occurs in the supplied
 * verified chain; this permits an offline replica to catch up across multiple contiguous
 * generations without weakening rollback or fork detection.</p>
 */
public interface ExternalSequenceAnchorBootstrapRootPublicationFloor {

    /** Persists or verifies the head of one complete, contiguous, fully replayed root chain. */
    void accept(VerifiedChain chain);

    /** @return whether the head survives process and application database restart */
    boolean durable();

    /**
     * Complete floor-visible chain derived from a cryptographically verified transition bundle.
     *
     * @param schemaVersion floor chain protocol generation
     * @param scopeId stable fleet scope
     * @param rootSetId exact bootstrap-root chain identity
     * @param generations complete ordered sequence 1..head
     */
    record VerifiedChain(
            String schemaVersion,
            String scopeId,
            String rootSetId,
            List<Generation> generations) {

        /** Current verified floor-chain generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootVerifiedChain.v1";

        /** Enforces a complete bounded chain with exact predecessor links. */
        public VerifiedChain {
            schemaVersion = ExternalSequenceAnchorBootstrapRootGenesis.normalized(schemaVersion);
            scopeId = ExternalSequenceAnchorBootstrapRootGenesis.normalized(scopeId);
            rootSetId = ExternalSequenceAnchorBootstrapRootGenesis.normalized(rootSetId);
            generations = generations == null ? List.of() : List.copyOf(generations);
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || generations.isEmpty()
                    || generations.size()
                    > ExternalSequenceAnchorBootstrapRootBundle.MAXIMUM_TRANSITIONS) {
                throw new IllegalArgumentException(
                        "Invalid external bootstrap-root verified chain");
            }
            String previous = "";
            for (int index = 0; index < generations.size(); index++) {
                Generation generation = Objects.requireNonNull(
                        generations.get(index), "generation");
                if (!scopeId.equals(generation.scopeId())
                        || !rootSetId.equals(generation.rootSetId())
                        || generation.sequence() != index + 1L
                        || !previous.equals(generation.previousMaterialFingerprint())) {
                    throw new IllegalArgumentException(
                            "External bootstrap-root verified chain is discontinuous");
                }
                previous = generation.materialFingerprint();
            }
        }

        /** @return final fully verified generation */
        public Generation head() {
            return generations.getLast();
        }
    }

    /** Exact verified chain head submitted to durable monotonic storage. */
    record Generation(
            String schemaVersion,
            String scopeId,
            String rootSetId,
            long sequence,
            String materialFingerprint,
            String previousMaterialFingerprint) {

        /** Current bootstrap-root floor candidate generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootGeneration.v1";

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Enforces canonical genesis-or-successor head shape. */
        public Generation {
            schemaVersion = ExternalSequenceAnchorBootstrapRootGenesis.normalized(schemaVersion);
            scopeId = ExternalSequenceAnchorBootstrapRootGenesis.normalized(scopeId);
            rootSetId = ExternalSequenceAnchorBootstrapRootGenesis.normalized(rootSetId);
            materialFingerprint = ExternalSequenceAnchorBootstrapRootGenesis.normalized(
                    materialFingerprint);
            previousMaterialFingerprint =
                    ExternalSequenceAnchorBootstrapRootGenesis.normalized(
                            previousMaterialFingerprint);
            boolean predecessorShape = sequence == 1 && previousMaterialFingerprint.isEmpty()
                    || sequence > 1
                    && FINGERPRINT.matcher(previousMaterialFingerprint).matches();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(scopeId).matches()
                    || !IDENTIFIER.matcher(rootSetId).matches()
                    || sequence < 1
                    || !FINGERPRINT.matcher(materialFingerprint).matches()
                    || !predecessorShape) {
                throw new IllegalArgumentException(
                        "Invalid external sequence-anchor bootstrap-root generation");
            }
        }
    }
}
