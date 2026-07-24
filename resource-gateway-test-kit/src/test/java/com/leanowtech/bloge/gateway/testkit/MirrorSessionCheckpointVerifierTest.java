package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorSessionCheckpointVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private MirrorSessionCheckpointVerifier verifier;
    private Fixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        verifier = new MirrorSessionCheckpointVerifier();
        fixture = fixture();
    }

    @Test
    void independentlyVerifiesBundleAndExactRecoveryResult() {
        MirrorSessionCheckpointVerifier.VerificationResult verified =
                verifier.verify(fixture.bundle(), fixture.key());
        MirrorSessionCheckpointVerifier.VerifiedRecoveryResult recovery =
                verifier.verifyRecoveryResult(
                        fixture.recovery(), fixture.bundle());

        assertThat(verified.verified()).isTrue();
        assertThat(verified.checkpointId())
                .isEqualTo("checkpoint-1");
        assertThat(verified.sessionId())
                .isEqualTo("refund-session-1");
        assertThat(verified.stateRevision()).isZero();
        assertThat(recovery.sessionId())
                .isEqualTo(verified.sessionId());
        assertThat(recovery.storeGenerationFingerprint())
                .isEqualTo(
                        verified.storeGenerationFingerprint());
        assertThat(fixture.bundle().toString())
                .doesNotContain("O-100")
                .doesNotContain("entities")
                .doesNotContain("processedCommands");
    }

    @Test
    void rejectsCheckpointBundleAndSignatureTampering() {
        ObjectNode stateTampered = fixture.bundle().deepCopy();
        ((ObjectNode) stateTampered.path("checkpoint"))
                .put("stateFingerprint",
                        "sha256:" + "9".repeat(64));
        ObjectNode signatureTampered = fixture.bundle().deepCopy();
        ((ObjectNode) signatureTampered.path("attestation"))
                .put("signature", Base64.getEncoder()
                        .encodeToString(new byte[64]));

        assertThat(verifier.verify(
                stateTampered, fixture.key()).verified()).isFalse();
        assertThat(verifier.verify(
                signatureTampered, fixture.key()).verified()).isFalse();
    }

    @Test
    void separatesMissingAndPolicyRejectedVerificationKeys()
            throws Exception {
        MirrorSessionCheckpointVerifier.VerificationResult missing =
                verifier.verify(fixture.bundle(), null);
        EvidenceVerificationKey wrongAlgorithm =
                new EvidenceVerificationKey(
                        TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1,
                        fixture.key().keyId(), "RSA",
                        fixture.key().encodedPublicKey(),
                        fixture.key().createdAt(),
                        "ACTIVE", "TEST");

        assertThat(missing.outcome())
                .isEqualTo(
                        MirrorSessionCheckpointVerifier.Outcome
                                .KEY_UNAVAILABLE);
        assertThat(verifier.verify(
                fixture.bundle(), wrongAlgorithm).outcome())
                .isEqualTo(
                        MirrorSessionCheckpointVerifier.Outcome
                                .POLICY_REJECTED);
    }

    @Test
    void recoveryMustMatchExactCheckpointAndDescriptorHead() {
        ObjectNode stale = fixture.recovery().deepCopy();
        ((ObjectNode) stale.path("runBinding"))
                .put("expectedStateFingerprint",
                        "sha256:" + "8".repeat(64));
        seal(stale);

        assertThatThrownBy(() ->
                verifier.verifyRecoveryResult(
                        stale, fixture.bundle()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "RG.MIRROR.CLIENT.SESSION_RECOVERY_CLOSURE_INVALID");
    }

    static Fixture fixture() throws Exception {
        JsonNode protocolFixture =
                CapabilityMirrorProtocol.statefulRefundFixture();
        JsonNode state = protocolFixture.path("initialState");
        ObjectNode descriptor = descriptor(state);
        ObjectNode generation = JSON.createObjectNode()
                .put("schemaVersion",
                        CapabilityMirrorProtocol
                                .MIRROR_SESSION_STORE_GENERATION_V1)
                .put("generationId", "store-generation-1")
                .put("schemaRevision", 1)
                .put("createdAt", "2026-07-23T23:58:00Z");
        seal(generation);
        ObjectNode checkpoint = JSON.createObjectNode()
                .put("schemaVersion",
                        CapabilityMirrorProtocol
                                .MIRROR_SESSION_CHECKPOINT_V1)
                .put("checkpointId", "checkpoint-1")
                .put("sessionId",
                        state.path("sessionId").asText())
                .put("planFingerprint",
                        state.path("planFingerprint").asText())
                .put("stateRevision",
                        state.path("stateRevision").asLong())
                .put("logicalClock",
                        state.path("logicalClock").asText())
                .put("worldFingerprint",
                        state.path("worldFingerprint").asText())
                .put("stateFingerprint",
                        state.path("fingerprint").asText())
                .put("payloadFingerprint",
                        "sha256:" + "5".repeat(64))
                .put("descriptorFingerprint",
                        descriptor.path("fingerprint").asText())
                .put("sessionCreatedAt",
                        descriptor.path("createdAt").asText())
                .put("sessionUpdatedAt",
                        descriptor.path("updatedAt").asText())
                .put("sessionExpiresAt",
                        descriptor.path("expiresAt").asText())
                .put("checkpointedAt",
                        "2026-07-24T00:02:00Z");
        checkpoint.set("scope", state.path("scope").deepCopy());
        checkpoint.set(
                "storeGeneration", generation.deepCopy());
        checkpoint.set(
                "stateModelRef",
                state.path("stateModelRef").deepCopy());
        checkpoint.putArray("stateReadRefs");
        checkpoint.set(
                "writeEffectRefs",
                state.path("writeEffectRefs").deepCopy());
        seal(checkpoint);

        KeyPair keyPair = KeyPairGenerator.getInstance(
                "Ed25519").generateKeyPair();
        String keyId = "checkpoint-key-1";
        String signedAt = "2026-07-24T00:03:00Z";
        ObjectNode signatureMaterial =
                JSON.createObjectNode()
                        .put("domain",
                                "RESOURCE_GATEWAY_MIRROR_SESSION_CHECKPOINT_V1")
                        .put("schemaVersion",
                                CapabilityMirrorProtocol
                                        .MIRROR_SESSION_CHECKPOINT_ATTESTATION_V1)
                        .put("checkpointId", "checkpoint-1")
                        .put("checkpointFingerprint",
                                checkpoint.path(
                                        "fingerprint").asText())
                        .put("signedAt", signedAt);
        String materialFingerprint =
                EvidenceVerificationSupport.sha256Bounded(
                        signatureMaterial, 8 * 1024);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(materialFingerprint.getBytes(
                StandardCharsets.UTF_8));
        ObjectNode attestation = JSON.createObjectNode()
                .put("schemaVersion",
                        CapabilityMirrorProtocol
                                .MIRROR_SESSION_CHECKPOINT_ATTESTATION_V1)
                .put("checkpointId", "checkpoint-1")
                .put("checkpointFingerprint",
                        checkpoint.path("fingerprint").asText())
                .put("signedAt", signedAt)
                .put("keyId", keyId)
                .put("algorithm", "Ed25519")
                .put("signature", Base64.getEncoder()
                        .encodeToString(signer.sign()))
                .put("independentlyVerifiable", true);
        ObjectNode bundleMaterial = JSON.createObjectNode()
                .put("schemaVersion",
                        CapabilityMirrorProtocol
                                .MIRROR_SESSION_CHECKPOINT_BUNDLE_V1)
                .put("payloadPolicy", "HASH_ONLY");
        bundleMaterial.set(
                "checkpoint", checkpoint.deepCopy());
        bundleMaterial.set(
                "attestation", attestation.deepCopy());
        ObjectNode bundle = bundleMaterial.deepCopy()
                .put("bundleFingerprint",
                        EvidenceVerificationSupport.sha256Bounded(
                                bundleMaterial, 5 * 1024 * 1024));

        ObjectNode runBinding = JSON.createObjectNode()
                .put("sessionId",
                        descriptor.path("sessionId").asText())
                .put("expectedStateFingerprint",
                        descriptor.path(
                                "stateFingerprint").asText());
        ObjectNode recovery = JSON.createObjectNode()
                .put("schemaVersion",
                        CapabilityMirrorProtocol
                                .MIRROR_SESSION_RECOVERY_RESULT_V1)
                .put("recoveryId", "recovery-1")
                .put("checkpointId", "checkpoint-1")
                .put("checkpointFingerprint",
                        checkpoint.path("fingerprint").asText())
                .put("storeGenerationFingerprint",
                        generation.path("fingerprint").asText())
                .put("recoveredAt",
                        "2026-07-24T00:04:00Z");
        recovery.set("descriptor", descriptor);
        recovery.set("runBinding", runBinding);
        seal(recovery);
        EvidenceVerificationKey key =
                new EvidenceVerificationKey(
                        TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1,
                        keyId, "Ed25519",
                        Base64.getEncoder().encodeToString(
                                keyPair.getPublic().getEncoded()),
                        Instant.parse("2026-07-23T23:59:00Z"),
                        "ACTIVE", "TEST");
        return new Fixture(bundle, recovery, key);
    }

    private static ObjectNode descriptor(JsonNode state) {
        ObjectNode descriptor = JSON.createObjectNode()
                .put("schemaVersion",
                        CapabilityMirrorProtocol
                                .MIRROR_SESSION_DESCRIPTOR_V1)
                .put("sessionId",
                        state.path("sessionId").asText())
                .put("planFingerprint",
                        state.path("planFingerprint").asText())
                .put("stateRevision",
                        state.path("stateRevision").asLong())
                .put("status", "ACTIVE")
                .put("worldFingerprint",
                        state.path("worldFingerprint").asText())
                .put("stateFingerprint",
                        state.path("fingerprint").asText())
                .put("createdAt", "2026-07-24T00:00:00Z")
                .put("updatedAt", "2026-07-24T00:01:00Z")
                .put("expiresAt", "2026-07-24T01:00:00Z")
                .putNull("destroyedAt");
        descriptor.set("scope", state.path("scope").deepCopy());
        descriptor.set(
                "stateModelRef",
                state.path("stateModelRef").deepCopy());
        descriptor.set(
                "writeEffectRefs",
                state.path("writeEffectRefs").deepCopy());
        return seal(descriptor);
    }

    static ObjectNode seal(ObjectNode value) {
        value.put("fingerprint", "");
        value.put("fingerprint",
                EvidenceVerificationSupport.sha256(value));
        return value;
    }

    record Fixture(
            ObjectNode bundle,
            ObjectNode recovery,
            EvidenceVerificationKey key
    ) {
    }
}
