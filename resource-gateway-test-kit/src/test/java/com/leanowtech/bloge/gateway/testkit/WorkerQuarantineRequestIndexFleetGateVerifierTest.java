package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerQuarantineRequestIndexFleetGateVerifierTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = EvidenceTrustTestFixtures.NOW;
    private static final String CHALLENGE = "deployment_gate_challenge_000001";
    private static final String SCOPE = "sha256:" + "b".repeat(64);
    private static final String ARTIFACT = "sha256:" + "a".repeat(64);
    private static final String INSTANCE_A = "rg-staging-0";
    private static final String INSTANCE_B = "rg-staging-1";
    private static final String STARTUP_A = "11111111-1111-1111-1111-111111111111";
    private static final String STARTUP_B = "22222222-2222-2222-2222-222222222222";

    private EvidenceTrustTestFixtures.Fixture fixture;
    private EvidenceVerificationKeySet keySet;
    private WorkerQuarantineRequestIndexFleetPolicy policy;
    private WorkerQuarantineRequestIndexFleetGateVerifier verifier;

    @BeforeEach
    void setUp() {
        fixture = EvidenceTrustTestFixtures.fixture();
        keySet = EvidenceVerificationKeySet.fromPayload(fixture.keySet());
        policy = policy(Set.of(INSTANCE_A, INSTANCE_B), fixture.keySetFingerprint());
        verifier = new WorkerQuarantineRequestIndexFleetGateVerifier(
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void verifiesEveryExactInstanceAgainstOnePinnedKeySetAndCohortPolicy() {
        var result = verifier.verify(List.of(
                proof(INSTANCE_A, STARTUP_A, NOW.minusSeconds(10), material -> { }),
                proof(INSTANCE_B, STARTUP_B, NOW.minusSeconds(8), material -> { })),
                policy, keySet);

        assertThat(result.verified()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("VERIFIED");
        assertThat(result.expectedInstances()).isEqualTo(2);
        assertThat(result.observedInstances()).isEqualTo(2);
        assertThat(result.verifiedInstances()).isEqualTo(2);
        assertThat(result.keyId()).isEqualTo("evidence-key-a");
    }

    @Test
    void verifiesDualReadFleetCanEnterKeyedOnlyWithLiveKeyedRowsAndNoLegacyRows() {
        WorkerQuarantineRequestIndexFleetPolicy keyedOnlyPolicy =
                WorkerQuarantineRequestIndexFleetPolicy.strict(
                        CHALLENGE, SCOPE, WorkerQuarantineRequestIndexReplicaProof.Mode.KEYED_ONLY,
                        ARTIFACT, "1.0", Set.of(INSTANCE_A, INSTANCE_B),
                        fixture.keySetFingerprint());

        var result = verifier.verify(List.of(
                proof(INSTANCE_A, STARTUP_A, NOW.minusSeconds(10),
                        WorkerQuarantineRequestIndexFleetGateVerifierTest::keyedOnlyReady),
                proof(INSTANCE_B, STARTUP_B, NOW.minusSeconds(9),
                        WorkerQuarantineRequestIndexFleetGateVerifierTest::keyedOnlyReady)),
                keyedOnlyPolicy, keySet);

        assertThat(result.verified()).isTrue();
        assertThat(result.verifiedInstances()).isEqualTo(2);
    }

    @Test
    void rejectsMissingAndUnexpectedInstancesAgainstExternalInventory() {
        var missing = verifier.verify(List.of(
                proof(INSTANCE_A, STARTUP_A, NOW.minusSeconds(10), material -> { })),
                policy, keySet);
        var unexpected = verifier.verify(List.of(
                proof(INSTANCE_A, STARTUP_A, NOW.minusSeconds(10), material -> { }),
                proof("rg-staging-2", STARTUP_B, NOW.minusSeconds(9), material -> { })),
                policy, keySet);

        assertThat(missing.reasonCode()).isEqualTo("EXPECTED_INSTANCE_PROOF_MISSING");
        assertThat(missing.instanceId()).isEqualTo(INSTANCE_B);
        assertThat(unexpected.reasonCode()).isEqualTo("UNEXPECTED_INSTANCE_PROOF");
        assertThat(unexpected.instanceId()).isEqualTo("rg-staging-2");
    }

    @Test
    void rejectsDuplicateServingSlotAndDuplicateProcessStart() {
        var duplicateInstance = verifier.verify(List.of(
                proof(INSTANCE_A, STARTUP_A, NOW.minusSeconds(10), material -> { }),
                proof(INSTANCE_A, STARTUP_B, NOW.minusSeconds(9), material -> { })),
                policy, keySet);
        var duplicateStartup = verifier.verify(List.of(
                proof(INSTANCE_A, STARTUP_A, NOW.minusSeconds(10), material -> { }),
                proof(INSTANCE_B, STARTUP_A, NOW.minusSeconds(9), material -> { })),
                policy, keySet);

        assertThat(duplicateInstance.reasonCode()).isEqualTo("DUPLICATE_INSTANCE_PROOF");
        assertThat(duplicateStartup.reasonCode()).isEqualTo("DUPLICATE_PROCESS_START_PROOF");
    }

    @Test
    void rejectsChallengeScopeArtifactProtocolTargetAndCurrentModeDrift() {
        assertProofReason(material -> material.put("challenge", "other_gate_challenge_000000000001"),
                "PROOF_CHALLENGE_MISMATCH");
        assertProofReason(material -> material.put("deploymentScopeFingerprint",
                "sha256:" + "c".repeat(64)), "PROOF_DEPLOYMENT_SCOPE_MISMATCH");
        assertProofReason(material -> material.put("artifactFingerprint",
                "sha256:" + "c".repeat(64)), "PROOF_ARTIFACT_MISMATCH");
        assertProofReason(material -> material.put("protocolVersion", "2.0"),
                "PROOF_PROTOCOL_VERSION_MISMATCH");
        assertProofReason(material -> material.put("targetMode", "KEYED_ONLY"),
                "PROOF_TARGET_MODE_MISMATCH");
        assertProofReason(material -> material.put("currentMode", "KEYED_ONLY"),
                "PROOF_CURRENT_MODE_MISMATCH");
    }

    @Test
    void rejectsSignedBlockerAndProducerClaimContradictingItsInventory() {
        var blocked = proof(INSTANCE_A, STARTUP_A, NOW.minusSeconds(10), material -> {
            material.put("transitionAllowed", false);
            material.putArray("blockers").add("LIVE_KEYED_ROWS_PRESENT");
        });
        var incompatible = proof(INSTANCE_A, STARTUP_A, NOW.minusSeconds(10), material -> {
            ObjectNode inventory = (ObjectNode) material.path("inventory");
            Instant expiry = NOW.plusSeconds(300);
            inventory.put("liveKeyedRows", 1);
            inventory.put("latestKeyedExpiry", expiry.toString());
            ObjectNode generation = inventory.putArray("keyedGenerations").addObject();
            generation.put("keyId", "request-key-v1");
            generation.put("liveRows", 1);
            generation.put("latestExpiry", expiry.toString());
        });

        assertThat(verifyWithHealthyPeer(blocked).reasonCode()).isEqualTo("PROOF_TRANSITION_BLOCKED");
        assertThat(verifyWithHealthyPeer(incompatible).reasonCode())
                .isEqualTo("PROOF_INVENTORY_TARGET_INCOMPATIBLE");
    }

    @Test
    void rejectsStaleFutureOversizedTtlAndWideObservationCohorts() {
        var stale = proof(INSTANCE_A, STARTUP_A, NOW.minusSeconds(200), material -> { });
        var future = proof(INSTANCE_A, STARTUP_A, NOW.plusSeconds(301), material -> { });
        var oversizedTtl = proof(INSTANCE_A, STARTUP_A, NOW.minusSeconds(10), material ->
                material.put("expiresAt", NOW.plusSeconds(291).toString()));
        var staleResult = verifier.verify(List.of(stale,
                proof(INSTANCE_B, STARTUP_B, NOW.minusSeconds(199), material -> { })),
                policy, keySet);
        var futureResult = verifier.verify(List.of(future,
                proof(INSTANCE_B, STARTUP_B, NOW.plusSeconds(302), material -> { })),
                policy, keySet);
        var wide = verifier.verify(List.of(
                proof(INSTANCE_A, STARTUP_A, NOW.minusSeconds(10), material -> { }),
                proof(INSTANCE_B, STARTUP_B, NOW.minusSeconds(50), material -> { })),
                policy, keySet);

        assertThat(staleResult.reasonCode()).isEqualTo("PROOF_STALE");
        assertThat(futureResult.reasonCode()).isEqualTo("PROOF_OBSERVATION_NOT_YET_VALID");
        assertThat(verifyWithHealthyPeer(oversizedTtl).reasonCode())
                .isEqualTo("PROOF_TTL_POLICY_REJECTED");
        assertThat(wide.reasonCode()).isEqualTo("PROOF_COHORT_OBSERVATION_SPREAD_EXCEEDED");
    }

    @Test
    void rejectsBadMaterialFingerprintBadSignatureAndUnknownSigningKey() {
        ObjectNode badFingerprintValue = proofValue(
                INSTANCE_A, STARTUP_A, NOW.minusSeconds(10), material -> { });
        String falseFingerprint = "sha256:" + "c".repeat(64);
        badFingerprintValue.put("materialFingerprint", falseFingerprint);
        ((ObjectNode) badFingerprintValue.path("seal"))
                .put("materialFingerprint", falseFingerprint);
        var badFingerprint = WorkerQuarantineRequestIndexReplicaProof.from(badFingerprintValue);

        ObjectNode badSignatureValue = proofValue(
                INSTANCE_A, STARTUP_A, NOW.minusSeconds(10), material -> { });
        ((ObjectNode) badSignatureValue.path("seal")).put("signature",
                Base64.getEncoder().encodeToString(new byte[64]));
        var badSignature = WorkerQuarantineRequestIndexReplicaProof.from(badSignatureValue);

        ObjectNode unknownKeyValue = proofValue(
                INSTANCE_A, STARTUP_A, NOW.minusSeconds(10), material -> { });
        ((ObjectNode) unknownKeyValue.path("seal")).put("keyId", "unknown-key");
        var unknownKey = WorkerQuarantineRequestIndexReplicaProof.from(unknownKeyValue);

        assertThat(verifyWithHealthyPeer(badFingerprint).reasonCode())
                .isEqualTo("PROOF_MATERIAL_FINGERPRINT_INVALID");
        assertThat(verifyWithHealthyPeer(badSignature).reasonCode())
                .isEqualTo("PROOF_SIGNATURE_INVALID");
        assertThat(verifyWithHealthyPeer(unknownKey).reasonCode())
                .isEqualTo("PROOF_VERIFICATION_KEY_UNAVAILABLE");
    }

    @Test
    void rejectsUnavailableOrUnpinnedKeySetBeforeTrustingAnySeal() {
        List<WorkerQuarantineRequestIndexReplicaProof> proofs = List.of(
                proof(INSTANCE_A, STARTUP_A, NOW.minusSeconds(10), material -> { }),
                proof(INSTANCE_B, STARTUP_B, NOW.minusSeconds(9), material -> { }));
        var unavailable = verifier.verify(proofs, policy, null);
        var wrongPin = verifier.verify(proofs,
                policy(Set.of(INSTANCE_A, INSTANCE_B), "sha256:" + "d".repeat(64)), keySet);

        assertThat(unavailable.outcome())
                .isEqualTo(WorkerQuarantineRequestIndexFleetGateVerifier.Outcome.KEY_UNAVAILABLE);
        assertThat(unavailable.reasonCode()).isEqualTo("KEY_SET_UNAVAILABLE");
        assertThat(wrongPin.reasonCode()).isEqualTo("KEY_SET_PIN_MISMATCH");
    }

    @Test
    void rejectsNonCanonicalProofAndFleetPolicyShapesBeforeGateEvaluation() {
        ObjectNode unknownField = proofValue(
                INSTANCE_A, STARTUP_A, NOW.minusSeconds(10), material -> { });
        unknownField.put("privatePayload", "must-not-be-accepted");

        assertThatThrownBy(() -> WorkerQuarantineRequestIndexReplicaProof.from(unknownField))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Request-index replica proof is invalid")
                .hasMessageNotContaining("must-not-be-accepted");
        assertThatThrownBy(() -> new WorkerQuarantineRequestIndexFleetPolicy(
                CHALLENGE, SCOPE,
                WorkerQuarantineRequestIndexReplicaProof.Mode.DUAL_READ_KEYED_WRITE,
                ARTIFACT, "1.0", Set.of(), fixture.keySetFingerprint(), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertProofReason(Consumer<ObjectNode> mutation, String reason) {
        var candidate = proof(INSTANCE_A, STARTUP_A, NOW.minusSeconds(10), mutation);
        assertThat(verifyWithHealthyPeer(candidate).reasonCode()).isEqualTo(reason);
    }

    private WorkerQuarantineRequestIndexFleetGateVerifier.VerificationResult verifyWithHealthyPeer(
            WorkerQuarantineRequestIndexReplicaProof candidate) {
        return verifier.verify(List.of(candidate,
                proof(INSTANCE_B, STARTUP_B, NOW.minusSeconds(9), material -> { })),
                policy, keySet);
    }

    private WorkerQuarantineRequestIndexFleetPolicy policy(
            Set<String> expected, String trustedPin) {
        return WorkerQuarantineRequestIndexFleetPolicy.strict(
                CHALLENGE, SCOPE,
                WorkerQuarantineRequestIndexReplicaProof.Mode.DUAL_READ_KEYED_WRITE,
                ARTIFACT, "1.0", expected, trustedPin);
    }

    private WorkerQuarantineRequestIndexReplicaProof proof(
            String instanceId, String startupId, Instant observedAt,
            Consumer<ObjectNode> materialMutation) {
        return WorkerQuarantineRequestIndexReplicaProof.from(
                proofValue(instanceId, startupId, observedAt, materialMutation));
    }

    private ObjectNode proofValue(
            String instanceId, String startupId, Instant observedAt,
            Consumer<ObjectNode> materialMutation) {
        ObjectNode material = JSON.createObjectNode();
        material.put("schemaVersion",
                TestingProtocol.WORKER_QUARANTINE_REQUEST_INDEX_REPLICA_PROOF_MATERIAL_V1);
        material.put("challenge", CHALLENGE);
        material.put("deploymentScopeFingerprint", SCOPE);
        material.put("instanceId", instanceId);
        material.put("startupId", startupId);
        material.put("artifactFingerprint", ARTIFACT);
        material.put("protocolVersion", "1.0");
        material.put("currentMode", "LEGACY_READ_WRITE");
        material.put("targetMode", "DUAL_READ_KEYED_WRITE");
        ObjectNode inventory = material.putObject("inventory");
        inventory.put("observedAt", observedAt.toString());
        inventory.put("liveLegacyRows", 0);
        inventory.put("liveKeyedRows", 0);
        inventory.put("latestLegacyExpiry", Instant.EPOCH.toString());
        inventory.put("latestKeyedExpiry", Instant.EPOCH.toString());
        inventory.putArray("keyedGenerations");
        material.put("transitionAllowed", true);
        material.putArray("blockers");
        material.put("expiresAt", observedAt.plusSeconds(120).toString());
        materialMutation.accept(material);

        String fingerprint = EvidenceTrustTestFixtures.fingerprint(material);
        ObjectNode proof = JSON.createObjectNode();
        proof.put("schemaVersion",
                TestingProtocol.WORKER_QUARANTINE_REQUEST_INDEX_REPLICA_PROOF_V1);
        proof.set("material", material);
        proof.put("materialFingerprint", fingerprint);
        ObjectNode seal = proof.putObject("seal");
        seal.put("schemaVersion", "bloge.visualRunEvidenceSeal.v1");
        seal.put("materialFingerprint", fingerprint);
        seal.put("algorithm", "Ed25519");
        seal.put("keyId", "evidence-key-a");
        seal.put("signedAt", observedAt.plusSeconds(1).toString());
        seal.put("signature", sign(fixture.evidence(), fingerprint));
        return proof;
    }

    private static void keyedOnlyReady(ObjectNode material) {
        material.put("currentMode", "DUAL_READ_KEYED_WRITE");
        material.put("targetMode", "KEYED_ONLY");
        ObjectNode inventory = (ObjectNode) material.path("inventory");
        Instant expiry = NOW.plusSeconds(300);
        inventory.put("liveKeyedRows", 2);
        inventory.put("latestKeyedExpiry", expiry.toString());
        ObjectNode generation = inventory.putArray("keyedGenerations").addObject();
        generation.put("keyId", "request-key-v1");
        generation.put("liveRows", 2);
        generation.put("latestExpiry", expiry.toString());
    }

    private static String sign(KeyPair keyPair, String fingerprint) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair.getPrivate());
            signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
