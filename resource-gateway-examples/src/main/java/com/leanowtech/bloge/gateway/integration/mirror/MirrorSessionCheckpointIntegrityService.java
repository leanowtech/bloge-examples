package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCheckpointException.Code.DEPENDENCY_CONFLICT;
import static com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCheckpointException.Code.GENERATION_CONFLICT;
import static com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCheckpointException.Code.INVALID;
import static com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCheckpointException.Code.SIGNER_UNAVAILABLE;
import static com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCheckpointException.Code.STATE_CONFLICT;

/**
 * Canonical signing and exact-state verification boundary for Session checkpoint recovery.
 *
 * <p>The detached signature uses a checkpoint-specific domain and covers the checkpoint identity,
 * complete checkpoint fingerprint, and signing time. Recovery additionally compares the signed
 * material with one transactional encrypted-store snapshot. A cryptographically valid but stale,
 * drifted, or cross-generation checkpoint therefore fails closed.</p>
 */
public final class MirrorSessionCheckpointIntegrityService {
    /** Maximum canonical checkpoint admitted to signing or verification. */
    public static final int MAXIMUM_CHECKPOINT_BYTES = 4 * 1024 * 1024;
    /** Maximum canonical portable bundle admitted to verification. */
    public static final int MAXIMUM_BUNDLE_BYTES = 5 * 1024 * 1024;
    /** Maximum canonical recovery result. */
    public static final int MAXIMUM_RECOVERY_RESULT_BYTES = 2 * 1024 * 1024;
    private static final int MAXIMUM_SIGNATURE_MATERIAL_BYTES = 8 * 1024;
    private static final String SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_MIRROR_SESSION_CHECKPOINT_V1";

    private final ObjectMapper mapper;
    private final VisualEvidenceSigner signer;
    private final Clock clock;

    /**
     * Creates an exact checkpoint integrity boundary.
     *
     * @param mapper canonical protocol mapper
     * @param signer local or managed Ed25519 signing authority
     * @param clock trusted server checkpoint clock
     */
    public MirrorSessionCheckpointIntegrityService(
            ObjectMapper mapper, VisualEvidenceSigner signer, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.signer = signer == null
                ? VisualEvidenceSigner.unavailable() : signer;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Signs one transactional store snapshot without serializing its business payload.
     *
     * @param material exact store generation and immutable Session snapshot
     * @return independently verified portable checkpoint bundle
     */
    public MirrorSessionCheckpointBundle seal(
            MirrorSessionStateStore.CheckpointSnapshot material) {
        if (!signer.available()) {
            throw new MirrorSessionCheckpointException(SIGNER_UNAVAILABLE);
        }
        try {
            MirrorSessionStoreGeneration generation =
                    canonicalGeneration(material.generation());
            MirrorSessionStateStore.SessionSnapshot snapshot =
                    canonicalSnapshot(material.snapshot());
            Instant checkpointedAt = clock.instant();
            MirrorSessionCheckpoint checkpoint = checkpoint(
                    generation, snapshot, checkpointedAt);
            Instant signedAt = clock.instant();
            if (signedAt.isBefore(checkpointedAt)
                    || !snapshot.descriptor().expiresAt().isAfter(signedAt)) {
                throw new IllegalArgumentException(
                        "checkpoint signing time is outside the active Session");
            }
            String signatureMaterialFingerprint =
                    signatureMaterialFingerprint(
                            checkpoint.checkpointId(),
                            checkpoint.fingerprint(), signedAt);
            VisualRunEvidenceSeal seal =
                    signer.seal(signatureMaterialFingerprint);
            MirrorSessionCheckpointAttestation attestation =
                    new MirrorSessionCheckpointAttestation(
                            "", checkpoint.checkpointId(),
                            checkpoint.fingerprint(), signedAt,
                            seal.keyId(), seal.algorithm(),
                            seal.signature(), true);
            if (!verifyAttestation(attestation)) {
                throw new MirrorSessionCheckpointException(
                        SIGNER_UNAVAILABLE);
            }
            BundleMaterial bundleMaterial = new BundleMaterial(
                    MirrorSessionCheckpointBundle.SCHEMA_VERSION,
                    MirrorSessionCheckpointBundle.PayloadPolicy.HASH_ONLY,
                    checkpoint, attestation);
            MirrorSessionCheckpointBundle bundle =
                    new MirrorSessionCheckpointBundle(
                            "", fingerprint(bundleMaterial,
                            MAXIMUM_BUNDLE_BYTES),
                            MirrorSessionCheckpointBundle.PayloadPolicy.HASH_ONLY,
                            checkpoint, attestation);
            if (verify(bundle) != Verification.VERIFIED) {
                throw new MirrorSessionCheckpointException(
                        SIGNER_UNAVAILABLE);
            }
            return bundle;
        } catch (MirrorSessionCheckpointException expected) {
            throw expected;
        } catch (RuntimeException invalid) {
            throw new MirrorSessionCheckpointException(INVALID);
        }
    }

    /**
     * Independently verifies canonical checkpoint, bundle, and detached-signature integrity.
     *
     * @param value portable checkpoint bundle
     * @return bounded verification result
     */
    public Verification verify(MirrorSessionCheckpointBundle value) {
        if (value == null) {
            return Verification.INVALID;
        }
        try {
            MirrorSessionCheckpointBundle bundle =
                    canonicalBundle(value);
            MirrorSessionCheckpoint checkpoint = bundle.checkpoint();
            MirrorSessionStoreGenerationIntegrity.verify(
                    mapper, checkpoint.storeGeneration());
            String checkpointFingerprint = fingerprint(
                    checkpoint.withFingerprint(""),
                    MAXIMUM_CHECKPOINT_BYTES);
            if (!checkpointFingerprint.equals(checkpoint.fingerprint())) {
                return Verification.INVALID;
            }
            MirrorSessionCheckpointAttestation attestation =
                    bundle.attestation();
            if (!checkpoint.checkpointId().equals(
                    attestation.checkpointId())
                    || !checkpoint.fingerprint().equals(
                    attestation.checkpointFingerprint())
                    || attestation.signedAt().isBefore(
                    checkpoint.checkpointedAt())) {
                return Verification.INVALID;
            }
            BundleMaterial material = new BundleMaterial(
                    bundle.schemaVersion(), bundle.payloadPolicy(),
                    checkpoint, attestation);
            if (!fingerprint(material, MAXIMUM_BUNDLE_BYTES)
                    .equals(bundle.bundleFingerprint())) {
                return Verification.INVALID;
            }
            if (!signer.available()) {
                return Verification.UNAVAILABLE;
            }
            return verifyAttestation(attestation)
                    ? Verification.VERIFIED : Verification.INVALID;
        } catch (RuntimeException invalid) {
            return Verification.INVALID;
        }
    }

    /**
     * Compares a valid signed checkpoint with one exact transactional durable snapshot.
     *
     * @param bundle already decoded checkpoint bundle
     * @param current exact current store generation and Session snapshot
     */
    public void verifyCurrent(
            MirrorSessionCheckpointBundle bundle,
            MirrorSessionStateStore.CheckpointSnapshot current) {
        Verification verification = verify(bundle);
        if (verification == Verification.UNAVAILABLE) {
            throw new MirrorSessionCheckpointException(
                    SIGNER_UNAVAILABLE);
        }
        if (verification != Verification.VERIFIED) {
            throw new MirrorSessionCheckpointException(INVALID);
        }
        try {
            MirrorSessionCheckpoint checkpoint = bundle.checkpoint();
            MirrorSessionStoreGeneration generation =
                    canonicalGeneration(current.generation());
            MirrorSessionStateStore.SessionSnapshot snapshot =
                    canonicalSnapshot(current.snapshot());
            if (!checkpoint.storeGeneration().equals(generation)) {
                throw new MirrorSessionCheckpointException(
                        GENERATION_CONFLICT);
            }
            MirrorSessionPayload payload = snapshot.payload();
            MirrorSessionDescriptor descriptor = snapshot.descriptor();
            SessionStateSpace state = payload.state();
            List<MirrorArtifactRef> stateReads = payload.stateReadSpecs()
                    .stream().map(StateReadSpecIntegrity::reference)
                    .sorted(REF_ORDER).toList();
            if (!checkpoint.scope().equals(state.scope())
                    || !checkpoint.sessionId().equals(state.sessionId())
                    || !checkpoint.planFingerprint().equals(
                    state.planFingerprint())
                    || !checkpoint.stateModelRef().equals(
                    state.stateModelRef())
                    || !checkpoint.stateReadRefs().equals(stateReads)
                    || !checkpoint.writeEffectRefs().equals(
                    state.writeEffectRefs())) {
                throw new MirrorSessionCheckpointException(
                        DEPENDENCY_CONFLICT);
            }
            if (checkpoint.stateRevision() != state.stateRevision()
                    || !checkpoint.logicalClock().equals(
                    state.logicalClock())
                    || !checkpoint.worldFingerprint().equals(
                    state.worldFingerprint())
                    || !checkpoint.stateFingerprint().equals(
                    state.fingerprint())
                    || !checkpoint.payloadFingerprint().equals(
                    payload.fingerprint())
                    || !checkpoint.descriptorFingerprint().equals(
                    descriptor.fingerprint())
                    || !checkpoint.sessionCreatedAt().equals(
                    descriptor.createdAt())
                    || !checkpoint.sessionUpdatedAt().equals(
                    descriptor.updatedAt())
                    || !checkpoint.sessionExpiresAt().equals(
                    descriptor.expiresAt())) {
                throw new MirrorSessionCheckpointException(
                        STATE_CONFLICT);
            }
        } catch (MirrorSessionCheckpointException expected) {
            throw expected;
        } catch (RuntimeException invalid) {
            throw new MirrorSessionCheckpointException(INVALID);
        }
    }

    /**
     * Content-addresses and verifies one payload-free successful recovery result.
     *
     * @param value unsealed result
     * @return sealed result
     */
    public MirrorSessionRecoveryResult sealRecoveryResult(
            MirrorSessionRecoveryResult value) {
        try {
            MirrorSessionRecoveryResult sealed = value.withFingerprint(
                    fingerprint(value.withFingerprint(""),
                            MAXIMUM_RECOVERY_RESULT_BYTES));
            verifyRecoveryResult(sealed);
            return sealed;
        } catch (RuntimeException invalid) {
            throw new MirrorSessionCheckpointException(INVALID);
        }
    }

    /**
     * Verifies a recovery-result canonical fingerprint.
     *
     * @param value sealed payload-free result
     */
    public void verifyRecoveryResult(
            MirrorSessionRecoveryResult value) {
        Objects.requireNonNull(value, "value");
        MirrorSessionProtocolIntegrity.verifyDescriptor(
                mapper, value.descriptor());
        String expected = fingerprint(
                value.withFingerprint(""),
                MAXIMUM_RECOVERY_RESULT_BYTES);
        if (!expected.equals(value.fingerprint())) {
            throw new IllegalArgumentException(
                    "mirror Session recovery result fingerprint mismatch");
        }
    }

    /**
     * Reports whether the configured signing authority can create and verify checkpoints.
     *
     * @return {@code true} when checkpoint signing and verification are available
     */
    public boolean available() {
        return signer.available();
    }

    private MirrorSessionCheckpoint checkpoint(
            MirrorSessionStoreGeneration generation,
            MirrorSessionStateStore.SessionSnapshot snapshot,
            Instant checkpointedAt) {
        MirrorSessionPayload payload = snapshot.payload();
        MirrorSessionDescriptor descriptor = snapshot.descriptor();
        SessionStateSpace state = payload.state();
        MirrorSessionCheckpoint unsealed =
                new MirrorSessionCheckpoint(
                        "", "checkpoint-" + UUID.randomUUID(),
                        state.scope(), state.sessionId(), generation,
                        state.planFingerprint(), state.stateModelRef(),
                        payload.stateReadSpecs().stream()
                                .map(StateReadSpecIntegrity::reference)
                                .sorted(REF_ORDER).toList(),
                        state.writeEffectRefs(), state.stateRevision(),
                        state.logicalClock(), state.worldFingerprint(),
                        state.fingerprint(), payload.fingerprint(),
                        descriptor.fingerprint(), descriptor.createdAt(),
                        descriptor.updatedAt(), descriptor.expiresAt(),
                        checkpointedAt, "");
        return unsealed.withFingerprint(
                fingerprint(unsealed, MAXIMUM_CHECKPOINT_BYTES));
    }

    private MirrorSessionStoreGeneration canonicalGeneration(
            MirrorSessionStoreGeneration value) {
        try {
            MirrorSessionStoreGeneration detached = mapper.readValue(
                    mapper.writeValueAsBytes(value),
                    MirrorSessionStoreGeneration.class);
            MirrorSessionStoreGenerationIntegrity.verify(mapper, detached);
            return detached;
        } catch (Exception invalid) {
            throw new IllegalArgumentException(
                    "store generation cannot be canonically detached", invalid);
        }
    }

    private MirrorSessionStateStore.SessionSnapshot canonicalSnapshot(
            MirrorSessionStateStore.SessionSnapshot value) {
        try {
            MirrorSessionPayload payload = mapper.readValue(
                    mapper.writeValueAsBytes(value.payload()),
                    MirrorSessionPayload.class);
            MirrorSessionDescriptor descriptor = mapper.readValue(
                    mapper.writeValueAsBytes(value.descriptor()),
                    MirrorSessionDescriptor.class);
            MirrorSessionProtocolIntegrity.verify(mapper, payload);
            MirrorSessionProtocolIntegrity.verifyDescriptor(
                    mapper, descriptor);
            if (!payload.state().stateModelRef().equals(
                    descriptor.stateModelRef())
                    || !payload.state().writeEffectRefs().equals(
                    descriptor.writeEffectRefs())
                    || !payload.state().planFingerprint().equals(
                    descriptor.planFingerprint())) {
                throw new IllegalArgumentException(
                        "Session checkpoint dependency closure is invalid");
            }
            return new MirrorSessionStateStore.SessionSnapshot(
                    payload, descriptor);
        } catch (Exception invalid) {
            throw new IllegalArgumentException(
                    "Session snapshot cannot be canonically detached", invalid);
        }
    }

    private MirrorSessionCheckpointBundle canonicalBundle(
            MirrorSessionCheckpointBundle value) {
        try {
            return mapper.readValue(
                    mapper.writeValueAsBytes(value),
                    MirrorSessionCheckpointBundle.class);
        } catch (Exception invalid) {
            throw new IllegalArgumentException(
                    "checkpoint bundle cannot be canonically detached", invalid);
        }
    }

    private boolean verifyAttestation(
            MirrorSessionCheckpointAttestation attestation) {
        String materialFingerprint = signatureMaterialFingerprint(
                attestation.checkpointId(),
                attestation.checkpointFingerprint(),
                attestation.signedAt());
        VisualEvidenceSigner.Verification result = signer.verify(
                new VisualRunEvidenceSeal(
                        "", materialFingerprint,
                        attestation.algorithm(), attestation.keyId(),
                        attestation.signedAt(), attestation.signature()),
                materialFingerprint);
        return result.valid();
    }

    private String signatureMaterialFingerprint(
            String checkpointId,
            String checkpointFingerprint,
            Instant signedAt) {
        return fingerprint(new SignatureMaterial(
                        SIGNATURE_DOMAIN,
                        MirrorSessionCheckpointAttestation.SCHEMA_VERSION,
                        checkpointId, checkpointFingerprint, signedAt),
                MAXIMUM_SIGNATURE_MATERIAL_BYTES);
    }

    private String fingerprint(Object value, int maximumBytes) {
        return ProtocolFingerprint.ofBounded(
                mapper, value, maximumBytes);
    }

    private static final Comparator<MirrorArtifactRef> REF_ORDER =
            Comparator.comparing(MirrorArtifactRef::id)
                    .thenComparingLong(MirrorArtifactRef::revision);

    private record SignatureMaterial(
            String domain,
            String schemaVersion,
            String checkpointId,
            String checkpointFingerprint,
            Instant signedAt
    ) {
    }

    private record BundleMaterial(
            String schemaVersion,
            MirrorSessionCheckpointBundle.PayloadPolicy payloadPolicy,
            MirrorSessionCheckpoint checkpoint,
            MirrorSessionCheckpointAttestation attestation
    ) {
    }

    /** Independent trust result separating invalid material from signer availability. */
    public enum Verification {
        VERIFIED,
        INVALID,
        UNAVAILABLE
    }
}
