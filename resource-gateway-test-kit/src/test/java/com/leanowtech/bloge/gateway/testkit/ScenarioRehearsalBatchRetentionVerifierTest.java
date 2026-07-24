package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioRehearsalBatchRetentionVerifierTest {
    private static final ObjectMapper JSON =
            new ObjectMapper().findAndRegisterModules();
    private static final Instant SIGNED_AT =
            Instant.parse("2026-07-25T08:00:01Z");

    @Test
    void verifiesAnIndependentSignedBatchDeletionProof()
            throws Exception {
        KeyPair keyPair = keyPair();
        ObjectNode state = signedState(keyPair);

        ScenarioRehearsalBatchRetentionVerifier.VerificationResult
                result =
                new ScenarioRehearsalBatchRetentionVerifier()
                        .verify(state, key(keyPair));

        assertThat(result.verified()).isTrue();
        assertThat(result.verifiedDeletionProof()).isTrue();
        assertThat(result.jobId()).isEqualTo(jobId());
        assertThat(result.manifestFingerprint())
                .isEqualTo(fingerprint('9'));
        assertThat(result.eventFingerprint())
                .isEqualTo(state.path("latestEvent")
                        .path("evidenceSeal")
                        .path("materialFingerprint").asText());
        assertThat(result.evidenceBundleFingerprint())
                .isEqualTo(fingerprint('a'));
    }

    @Test
    void distinguishesUnavailableKeysFromTamperedProofs()
            throws Exception {
        KeyPair keyPair = keyPair();
        ObjectNode state = signedState(keyPair);
        ScenarioRehearsalBatchRetentionVerifier verifier =
                new ScenarioRehearsalBatchRetentionVerifier();

        assertThat(verifier.verify(state, null).outcome())
                .isEqualTo(
                        ScenarioRehearsalBatchRetentionVerifier
                                .Outcome.KEY_UNAVAILABLE);

        ObjectNode tampered = state.deepCopy();
        tampered.withObject("latestEvent")
                .put("deletedItemCount", 4);
        assertThat(verifier.verify(
                tampered, key(keyPair)).reasonCode())
                .isEqualTo(
                        "SCENARIO_BATCH_RETENTION_FINGERPRINT_INVALID");
    }

    @Test
    void rejectsProjectionDriftAndInvalidDeletionSemantics()
            throws Exception {
        KeyPair keyPair = keyPair();
        ScenarioRehearsalBatchRetentionVerifier verifier =
                new ScenarioRehearsalBatchRetentionVerifier();

        ObjectNode drifted = signedState(keyPair);
        drifted.put("manifestFingerprint", fingerprint('7'));
        assertThat(verifier.verify(
                drifted, key(keyPair)).reasonCode())
                .isEqualTo("SCENARIO_BATCH_RETENTION_PROOF_INVALID");

        ObjectNode heldPurge = signedState(keyPair);
        heldPurge.withArray("activeHoldIds")
                .add("legal-hold-a");
        assertThat(verifier.verify(
                heldPurge, key(keyPair)).reasonCode())
                .isEqualTo(
                        "SCENARIO_BATCH_RETENTION_SCHEMA_INVALID");
    }

    @Test
    void rejectsNonCanonicalHoldOrdering()
            throws Exception {
        KeyPair keyPair = keyPair();
        ObjectNode event = registrationEvent();
        String eventFingerprint =
                ScenarioRehearsalBatchRetentionVerifier
                        .eventFingerprint(event);
        ObjectNode seal = event.withObject("evidenceSeal");
        seal.put("materialFingerprint", eventFingerprint);
        seal.put("signature", sign(keyPair, eventFingerprint));

        ObjectNode state = retainedState(event);
        state.withArray("activeHoldIds")
                .add("hold-b")
                .add("hold-a");

        assertThat(new ScenarioRehearsalBatchRetentionVerifier()
                .verify(state, key(keyPair)).reasonCode())
                .isEqualTo("SCENARIO_BATCH_RETENTION_PROOF_INVALID");
    }

    @Test
    void rejectsAKeyThatWasNotActiveAtSigningTime()
            throws Exception {
        KeyPair keyPair = keyPair();
        EvidenceVerificationKey future =
                new EvidenceVerificationKey(
                        TestingProtocol
                                .EVIDENCE_VERIFICATION_KEY_V1,
                        "scenario-batch-retention-key-1",
                        "Ed25519",
                        Base64.getEncoder().encodeToString(
                                keyPair.getPublic().getEncoded()),
                        SIGNED_AT.plusSeconds(3600),
                        "ACTIVE",
                        "test");

        assertThat(new ScenarioRehearsalBatchRetentionVerifier()
                .verify(signedState(keyPair), future).outcome())
                .isEqualTo(
                        ScenarioRehearsalBatchRetentionVerifier
                                .Outcome.POLICY_REJECTED);
    }

    private static ObjectNode signedState(KeyPair keyPair)
            throws Exception {
        ObjectNode event = purgeEvent();
        String eventFingerprint =
                ScenarioRehearsalBatchRetentionVerifier
                        .eventFingerprint(event);
        ObjectNode seal = event.withObject("evidenceSeal");
        seal.put("materialFingerprint", eventFingerprint);
        seal.put("signature", sign(keyPair, eventFingerprint));

        ObjectNode value = retainedState(event);
        value.put("status", "PURGED");
        value.put("revision", 2);
        value.putArray("activeHoldIds");
        return value;
    }

    private static ObjectNode retainedState(
            ObjectNode event) {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_RETENTION_STATE_V1);
        value.set("scope", scope());
        value.put("requestId", "batch-request-1");
        value.put("jobId", jobId());
        value.put("manifestFingerprint", fingerprint('9'));
        value.put("evidenceBundleFingerprint", fingerprint('a'));
        value.put("status", "RETAINED");
        value.put("revision", event.path("revision").asLong());
        value.put("retainUntil", "2026-07-24T08:00:00Z");
        value.putArray("activeHoldIds");
        value.put("updatedAt",
                event.path("occurredAt").asText());
        value.set("latestEvent", event);
        return value;
    }

    private static ObjectNode purgeEvent() {
        ObjectNode value = baseEvent();
        value.put("revision", 2);
        value.put("type", "PURGED");
        value.put("previousEventFingerprint", fingerprint('b'));
        value.put("deletedJobCount", 1);
        value.put("deletedItemCount", 3);
        value.put("deletedBatchEvidenceCount", 1);
        value.put("childEvidenceDisposition", "RETAINED");
        value.put("auditDisposition", "RETAINED");
        return value;
    }

    private static ObjectNode registrationEvent() {
        ObjectNode value = baseEvent();
        value.put("revision", 1);
        value.put("type", "RETENTION_REGISTERED");
        value.put("previousEventFingerprint", "");
        value.put("deletedJobCount", 0);
        value.put("deletedItemCount", 0);
        value.put("deletedBatchEvidenceCount", 0);
        value.put("childEvidenceDisposition", "NOT_APPLICABLE");
        value.put("auditDisposition", "NOT_APPLICABLE");
        return value;
    }

    private static ObjectNode baseEvent() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_RETENTION_EVENT_V1);
        value.put("eventId", "batch-retention-event-2");
        value.put("commandId", "purge-command-1");
        value.set("scope", scope());
        value.put("requestId", "batch-request-1");
        value.put("jobId", jobId());
        value.put("manifestFingerprint", fingerprint('9'));
        value.put("retainUntil", "2026-07-24T08:00:00Z");
        value.put("occurredAt", "2026-07-25T08:00:00Z");
        value.put("actorId", "governance-admin");
        value.put("reasonCode",
                "RG.MIRROR.REHEARSAL.BATCH_RETENTION_EXPIRED");
        value.put("holdId", "");
        value.put("evidenceBundleFingerprint", fingerprint('a'));
        ObjectNode seal = value.putObject("evidenceSeal");
        seal.put("schemaVersion", "bloge.visualRunEvidenceSeal.v1");
        seal.put("materialFingerprint", fingerprint('c'));
        seal.put("algorithm", "Ed25519");
        seal.put("keyId", "scenario-batch-retention-key-1");
        seal.put("signedAt", SIGNED_AT.toString());
        seal.put("signature", "placeholder");
        return value;
    }

    private static ObjectNode scope() {
        ObjectNode value = JSON.createObjectNode();
        value.put("tenantId", "tenant-a");
        value.put("organizationId", "org-a");
        value.put("projectId", "support");
        value.put("environmentId", "test");
        value.put("region", "sg");
        return value;
    }

    private static EvidenceVerificationKey key(
            KeyPair keyPair) {
        return new EvidenceVerificationKey(
                TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1,
                "scenario-batch-retention-key-1",
                "Ed25519",
                Base64.getEncoder().encodeToString(
                        keyPair.getPublic().getEncoded()),
                Instant.parse("2026-07-25T07:00:00Z"),
                "ACTIVE",
                "test");
    }

    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator
                .getInstance("Ed25519")
                .generateKeyPair();
    }

    private static String sign(
            KeyPair keyPair, String fingerprint)
            throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(
                fingerprint.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(
                signer.sign());
    }

    private static String jobId() {
        return "scenario-batch-" + "8".repeat(64);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
