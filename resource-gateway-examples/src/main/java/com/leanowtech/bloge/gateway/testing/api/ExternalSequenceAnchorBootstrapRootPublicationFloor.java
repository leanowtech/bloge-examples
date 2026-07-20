package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Durable monotonic floor for verified external-anchor bootstrap-root transition heads.
 *
 * <p>Only a fully replayed chain head may advance this floor. An empty floor accepts any head whose
 * complete genesis history was verified by the caller, allowing a new replica to join after many
 * rotations. An existing floor accepts only the exact current head or one contiguous successor,
 * preventing restart-time rollback, fork, and gap.</p>
 */
public interface ExternalSequenceAnchorBootstrapRootPublicationFloor {

    /** Persists or verifies one fully replayed root-chain head. */
    void accept(Generation generation);

    /** @return whether the head survives process and application database restart */
    boolean durable();

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
