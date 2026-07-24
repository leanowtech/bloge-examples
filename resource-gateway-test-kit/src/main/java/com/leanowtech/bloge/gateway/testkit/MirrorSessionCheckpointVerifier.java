package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

/**
 * Server-independent verifier for signed payload-free Session checkpoints and recovery results.
 *
 * <p>The verifier validates the packaged strict Schemas, re-derives the store-generation,
 * checkpoint, bundle, and recovery-result fingerprints, checks dependency ordering and time
 * closure, applies verification-key policy, and verifies the checkpoint-specific Ed25519
 * signature domain. It returns only payload-free identities suitable for CI and governance logs.</p>
 */
public final class MirrorSessionCheckpointVerifier {
    /** Maximum canonical checkpoint bytes admitted by the producer protocol. */
    public static final int MAXIMUM_CHECKPOINT_BYTES = 4 * 1024 * 1024;
    /** Maximum canonical checkpoint-bundle bytes admitted by the producer protocol. */
    public static final int MAXIMUM_BUNDLE_BYTES = 5 * 1024 * 1024;
    /** Maximum canonical recovery-result bytes admitted by the producer protocol. */
    public static final int MAXIMUM_RECOVERY_RESULT_BYTES = 2 * 1024 * 1024;
    private static final int MAXIMUM_SIGNATURE_MATERIAL_BYTES = 8 * 1024;
    private static final String SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_MIRROR_SESSION_CHECKPOINT_V1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final MirrorStateProtocolVerifier stateVerifier =
            new MirrorStateProtocolVerifier();

    /** Creates a server-independent Session checkpoint protocol verifier. */
    public MirrorSessionCheckpointVerifier() {
    }

    /** Bounded checkpoint verification outcome. */
    public enum Outcome {
        /** Schema, closure, fingerprints, key policy, and signature all passed. */
        VERIFIED,
        /** Structure, closure, fingerprint, or signature is invalid. */
        INVALID,
        /** The attestation verification key is unavailable. */
        KEY_UNAVAILABLE,
        /** Key lifecycle or algorithm policy rejects the material. */
        POLICY_REJECTED
    }

    /**
     * Payload-free verification result.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param checkpointId checkpoint identity, or blank when unavailable
     * @param sessionId Session identity, or blank when unavailable
     * @param stateRevision exact checkpoint revision, or {@code -1}
     * @param checkpointFingerprint checkpoint fingerprint, or blank
     * @param bundleFingerprint bundle fingerprint, or blank
     * @param storeGenerationFingerprint durable data-plane generation fingerprint, or blank
     * @param keyId attestation key identity, or blank
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String checkpointId,
            String sessionId,
            long stateRevision,
            String checkpointFingerprint,
            String bundleFingerprint,
            String storeGenerationFingerprint,
            String keyId
    ) {
        /** Normalizes one log-safe bounded result. */
        public VerificationResult {
            outcome = java.util.Objects.requireNonNull(
                    outcome, "outcome");
            reasonCode = normalized(reasonCode);
            checkpointId = normalized(checkpointId);
            sessionId = normalized(sessionId);
            checkpointFingerprint = normalized(
                    checkpointFingerprint);
            bundleFingerprint = normalized(bundleFingerprint);
            storeGenerationFingerprint = normalized(
                    storeGenerationFingerprint);
            keyId = normalized(keyId);
            if (!reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")
                    || stateRevision < -1) {
                throw new IllegalArgumentException(
                        "checkpoint verification result is invalid");
            }
        }

        /**
         * Reports whether every independent checkpoint verification step passed.
         *
         * @return {@code true} only for a verified result
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Independently verifies one signed checkpoint with its resolved public key.
     *
     * @param bundle decoded strict checkpoint bundle
     * @param key public key selected by attestation key id; may be {@code null}
     * @return payload-free bounded result
     */
    public VerificationResult verify(
            JsonNode bundle, EvidenceVerificationKey key) {
        Coordinates coordinates = Coordinates.from(bundle);
        try {
            CapabilityMirrorSchemaValidator.require(
                    bundle,
                    CapabilityMirrorProtocol
                            .MIRROR_SESSION_CHECKPOINT_BUNDLE_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.SESSION_CHECKPOINT_SCHEMA_INVALID");
            JsonNode checkpoint = bundle.path("checkpoint");
            JsonNode attestation = bundle.path("attestation");
            verifyGeneration(checkpoint.path("storeGeneration"));
            requireFingerprint(
                    checkpoint, "fingerprint",
                    MAXIMUM_CHECKPOINT_BYTES,
                    "MIRROR_SESSION_CHECKPOINT_FINGERPRINT_INVALID");
            verifyDependencyClosure(checkpoint);
            verifyTimeClosure(checkpoint, attestation);
            if (!checkpoint.path("checkpointId").asText().equals(
                    attestation.path("checkpointId").asText())
                    || !checkpoint.path("fingerprint").asText().equals(
                    attestation.path(
                            "checkpointFingerprint").asText())) {
                return result(Outcome.INVALID,
                        "MIRROR_SESSION_CHECKPOINT_ATTESTATION_IDENTITY_INVALID",
                        coordinates);
            }
            if (!EvidenceVerificationSupport.sha256Bounded(
                    bundleMaterial(bundle), MAXIMUM_BUNDLE_BYTES)
                    .equals(bundle.path(
                            "bundleFingerprint").asText())) {
                return result(Outcome.INVALID,
                        "MIRROR_SESSION_CHECKPOINT_BUNDLE_FINGERPRINT_INVALID",
                        coordinates);
            }
            if (key == null) {
                return result(Outcome.KEY_UNAVAILABLE,
                        "MIRROR_SESSION_CHECKPOINT_KEY_UNAVAILABLE",
                        coordinates);
            }
            if (!key.keyId().equals(
                    attestation.path("keyId").asText())) {
                return result(Outcome.INVALID,
                        "MIRROR_SESSION_CHECKPOINT_KEY_ID_MISMATCH",
                        coordinates);
            }
            Instant signedAt = instant(
                    attestation.path("signedAt"),
                    "MIRROR_SESSION_CHECKPOINT_TIME_INVALID");
            if (!"Ed25519".equals(key.algorithm())
                    || !key.algorithm().equals(
                    attestation.path("algorithm").asText())
                    || !key.verificationAllowed()
                    || signedAt.isBefore(key.createdAt().minus(
                    EvidenceVerificationSupport.KEY_CREATION_SKEW))) {
                return result(Outcome.POLICY_REJECTED,
                        "MIRROR_SESSION_CHECKPOINT_KEY_POLICY_REJECTED",
                        coordinates);
            }
            String materialFingerprint =
                    EvidenceVerificationSupport.sha256Bounded(
                            signatureMaterial(attestation),
                            MAXIMUM_SIGNATURE_MATERIAL_BYTES);
            if (!EvidenceVerificationSupport.verifyEd25519(
                    materialFingerprint,
                    attestation.path("signature").asText(),
                    key.encodedPublicKey())) {
                return result(Outcome.INVALID,
                        "MIRROR_SESSION_CHECKPOINT_SIGNATURE_INVALID",
                        coordinates);
            }
            return result(Outcome.VERIFIED, "VERIFIED", coordinates);
        } catch (GeneralSecurityException | RuntimeException invalid) {
            return result(Outcome.INVALID,
                    "MIRROR_SESSION_CHECKPOINT_MATERIAL_INVALID",
                    coordinates);
        }
    }

    /**
     * Verifies one successful recovery result against the exact submitted checkpoint.
     *
     * @param value decoded recovery-result payload
     * @param checkpointBundle exact locally verified checkpoint bundle
     * @return payload-free exact recovery identity
     */
    public VerifiedRecoveryResult verifyRecoveryResult(
            JsonNode value, JsonNode checkpointBundle) {
        CapabilityMirrorSchemaValidator.require(
                value,
                CapabilityMirrorProtocol
                        .MIRROR_SESSION_RECOVERY_RESULT_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SESSION_RECOVERY_SCHEMA_INVALID");
        CapabilityMirrorSchemaValidator.require(
                checkpointBundle,
                CapabilityMirrorProtocol
                        .MIRROR_SESSION_CHECKPOINT_BUNDLE_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SESSION_CHECKPOINT_SCHEMA_INVALID");
        requireFingerprint(
                value, "fingerprint",
                MAXIMUM_RECOVERY_RESULT_BYTES,
                "RG.MIRROR.CLIENT.SESSION_RECOVERY_FINGERPRINT_INVALID");
        MirrorStateProtocolVerifier.VerifiedSessionDescriptor descriptor =
                stateVerifier.verifySessionDescriptor(
                        value.path("descriptor"));
        JsonNode checkpoint = checkpointBundle.path("checkpoint");
        JsonNode binding = value.path("runBinding");
        Instant recoveredAt = instant(
                value.path("recoveredAt"),
                "RG.MIRROR.CLIENT.SESSION_RECOVERY_TIME_INVALID");
        if (!"ACTIVE".equals(descriptor.status())
                || !value.path("checkpointId").asText().equals(
                checkpoint.path("checkpointId").asText())
                || !value.path("checkpointFingerprint").asText().equals(
                checkpoint.path("fingerprint").asText())
                || !value.path(
                "storeGenerationFingerprint").asText().equals(
                checkpoint.at(
                        "/storeGeneration/fingerprint").asText())
                || !binding.path("sessionId").asText().equals(
                descriptor.sessionId())
                || !binding.path(
                "expectedStateFingerprint").asText().equals(
                descriptor.stateFingerprint())
                || !descriptor.sessionId().equals(
                checkpoint.path("sessionId").asText())
                || descriptor.stateRevision()
                != checkpoint.path("stateRevision").asLong()
                || !descriptor.stateFingerprint().equals(
                checkpoint.path("stateFingerprint").asText())
                || recoveredAt.isBefore(descriptor.updatedAt())
                || !descriptor.expiresAt().isAfter(recoveredAt)) {
            throw invalid(
                    "RG.MIRROR.CLIENT.SESSION_RECOVERY_CLOSURE_INVALID");
        }
        return new VerifiedRecoveryResult(
                value.path("schemaVersion").asText(),
                value.path("recoveryId").asText(),
                value.path("checkpointId").asText(),
                descriptor.sessionId(),
                descriptor.stateRevision(),
                descriptor.stateFingerprint(),
                value.path("storeGenerationFingerprint").asText(),
                value.path("fingerprint").asText(),
                recoveredAt);
    }

    private static void verifyGeneration(JsonNode generation) {
        CapabilityMirrorSchemaValidator.require(
                generation,
                CapabilityMirrorProtocol
                        .MIRROR_SESSION_STORE_GENERATION_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SESSION_STORE_GENERATION_SCHEMA_INVALID");
        requireFingerprint(
                generation, "fingerprint", 64 * 1024,
                "RG.MIRROR.CLIENT.SESSION_STORE_GENERATION_FINGERPRINT_INVALID");
    }

    private static void verifyDependencyClosure(
            JsonNode checkpoint) {
        if (!"STATE_MODEL".equals(
                checkpoint.at("/stateModelRef/kind").asText())) {
            throw invalid(
                    "RG.MIRROR.CLIENT.SESSION_CHECKPOINT_DEPENDENCY_INVALID");
        }
        verifyOrderedRefs(
                checkpoint.path("stateReadRefs"),
                "STATE_READ_SPEC");
        verifyOrderedRefs(
                checkpoint.path("writeEffectRefs"),
                "WRITE_EFFECT");
    }

    private static void verifyOrderedRefs(
            JsonNode refs, String kind) {
        Set<String> coordinates = new HashSet<>();
        JsonNode previous = null;
        for (JsonNode ref : refs) {
            String coordinate = ref.path("kind").asText()
                    + '\0' + ref.path("id").asText()
                    + '\0' + ref.path("revision").asLong()
                    + '\0' + ref.path("fingerprint").asText();
            if (!kind.equals(ref.path("kind").asText())
                    || !coordinates.add(coordinate)
                    || previous != null
                    && compareRef(previous, ref) >= 0) {
                throw invalid(
                        "RG.MIRROR.CLIENT.SESSION_CHECKPOINT_DEPENDENCY_INVALID");
            }
            previous = ref;
        }
    }

    private static int compareRef(
            JsonNode left, JsonNode right) {
        int id = left.path("id").asText().compareTo(
                right.path("id").asText());
        return id != 0 ? id : Long.compare(
                left.path("revision").asLong(),
                right.path("revision").asLong());
    }

    private static void verifyTimeClosure(
            JsonNode checkpoint, JsonNode attestation) {
        Instant created = instant(
                checkpoint.path("sessionCreatedAt"),
                "MIRROR_SESSION_CHECKPOINT_TIME_INVALID");
        Instant updated = instant(
                checkpoint.path("sessionUpdatedAt"),
                "MIRROR_SESSION_CHECKPOINT_TIME_INVALID");
        Instant expires = instant(
                checkpoint.path("sessionExpiresAt"),
                "MIRROR_SESSION_CHECKPOINT_TIME_INVALID");
        Instant checkpointed = instant(
                checkpoint.path("checkpointedAt"),
                "MIRROR_SESSION_CHECKPOINT_TIME_INVALID");
        Instant signed = instant(
                attestation.path("signedAt"),
                "MIRROR_SESSION_CHECKPOINT_TIME_INVALID");
        if (updated.isBefore(created)
                || checkpointed.isBefore(updated)
                || signed.isBefore(checkpointed)
                || !expires.isAfter(signed)) {
            throw invalid(
                    "MIRROR_SESSION_CHECKPOINT_TIME_INVALID");
        }
    }

    private static ObjectNode signatureMaterial(
            JsonNode attestation) {
        ObjectNode material = JSON.createObjectNode();
        material.put("domain", SIGNATURE_DOMAIN);
        material.put("schemaVersion",
                CapabilityMirrorProtocol
                        .MIRROR_SESSION_CHECKPOINT_ATTESTATION_V1);
        material.put("checkpointId",
                attestation.path("checkpointId").asText());
        material.put("checkpointFingerprint",
                attestation.path(
                        "checkpointFingerprint").asText());
        material.set("signedAt",
                attestation.path("signedAt").deepCopy());
        return material;
    }

    private static ObjectNode bundleMaterial(JsonNode bundle) {
        ObjectNode material = JSON.createObjectNode();
        material.set("schemaVersion",
                bundle.path("schemaVersion").deepCopy());
        material.set("payloadPolicy",
                bundle.path("payloadPolicy").deepCopy());
        material.set("checkpoint",
                bundle.path("checkpoint").deepCopy());
        material.set("attestation",
                bundle.path("attestation").deepCopy());
        return material;
    }

    private static void requireFingerprint(
            JsonNode value,
            String field,
            int maximumBytes,
            String failureCode) {
        ObjectNode material = value.deepCopy();
        material.put(field, "");
        if (!EvidenceVerificationSupport.sha256Bounded(
                material, maximumBytes)
                .equals(value.path(field).asText())) {
            throw invalid(failureCode);
        }
    }

    private static Instant instant(
            JsonNode value, String failureCode) {
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException invalid) {
            throw invalid(failureCode);
        }
    }

    private static VerificationResult result(
            Outcome outcome, String reason,
            Coordinates coordinates) {
        return new VerificationResult(
                outcome, reason,
                coordinates.checkpointId(),
                coordinates.sessionId(),
                coordinates.stateRevision(),
                coordinates.checkpointFingerprint(),
                coordinates.bundleFingerprint(),
                coordinates.storeGenerationFingerprint(),
                coordinates.keyId());
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record Coordinates(
            String checkpointId,
            String sessionId,
            long stateRevision,
            String checkpointFingerprint,
            String bundleFingerprint,
            String storeGenerationFingerprint,
            String keyId
    ) {
        private static Coordinates from(JsonNode bundle) {
            JsonNode checkpoint = bundle == null
                    ? JSON.createObjectNode()
                    : bundle.path("checkpoint");
            JsonNode attestation = bundle == null
                    ? JSON.createObjectNode()
                    : bundle.path("attestation");
            return new Coordinates(
                    checkpoint.path("checkpointId").asText(),
                    checkpoint.path("sessionId").asText(),
                    checkpoint.isObject()
                            && checkpoint.has("stateRevision")
                            ? checkpoint.path(
                            "stateRevision").asLong() : -1,
                    checkpoint.path("fingerprint").asText(),
                    bundle == null ? ""
                            : bundle.path(
                            "bundleFingerprint").asText(),
                    checkpoint.at(
                            "/storeGeneration/fingerprint").asText(),
                    attestation.path("keyId").asText());
        }
    }

    /**
     * Payload-free identity of a verified exact recovery result.
     *
     * @param schemaVersion verified result version
     * @param recoveryId recovery admission identity
     * @param checkpointId admitted checkpoint identity
     * @param sessionId exact Session identity
     * @param stateRevision exact recovered state revision
     * @param stateFingerprint exact recovered state fingerprint
     * @param storeGenerationFingerprint exact durable data-plane generation
     * @param fingerprint verified result fingerprint
     * @param recoveredAt recovery admission time
     */
    public record VerifiedRecoveryResult(
            String schemaVersion,
            String recoveryId,
            String checkpointId,
            String sessionId,
            long stateRevision,
            String stateFingerprint,
            String storeGenerationFingerprint,
            String fingerprint,
            Instant recoveredAt
    ) {
    }
}
