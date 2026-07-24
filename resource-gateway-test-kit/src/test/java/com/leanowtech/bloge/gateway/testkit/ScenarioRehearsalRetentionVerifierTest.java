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

class ScenarioRehearsalRetentionVerifierTest {
    private static final ObjectMapper JSON =
            new ObjectMapper().findAndRegisterModules();
    private static final Instant SIGNED_AT =
            Instant.parse("2026-07-25T08:00:01Z");

    @Test
    void verifiesAnIndependentSignedDeletionProof() throws Exception {
        KeyPair keyPair = keyPair();
        ObjectNode state = signedState(keyPair);
        EvidenceVerificationKey key = key(keyPair);

        ScenarioRehearsalRetentionVerifier.VerificationResult result =
                new ScenarioRehearsalRetentionVerifier()
                        .verify(state, key);

        assertThat(result.verified()).isTrue();
        assertThat(result.verifiedDeletionProof()).isTrue();
        assertThat(result.eventFingerprint())
                .isEqualTo(state.path("latestEvent")
                        .path("evidenceSeal")
                        .path("materialFingerprint").asText());
        assertThat(result.evidenceBundleFingerprint())
                .isEqualTo(fingerprint('a'));
    }

    @Test
    void distinguishesUnavailableKeysFromInvalidProofs()
            throws Exception {
        KeyPair keyPair = keyPair();
        ObjectNode state = signedState(keyPair);
        ScenarioRehearsalRetentionVerifier verifier =
                new ScenarioRehearsalRetentionVerifier();

        assertThat(verifier.verify(state, null).outcome())
                .isEqualTo(
                        ScenarioRehearsalRetentionVerifier
                                .Outcome.KEY_UNAVAILABLE);

        ObjectNode tampered = state.deepCopy();
        tampered.withObject("latestEvent")
                .put("actorId", "different-actor");
        assertThat(verifier.verify(tampered, key(keyPair)).reasonCode())
                .isEqualTo(
                        "SCENARIO_RETENTION_FINGERPRINT_INVALID");
    }

    @Test
    void rejectsProjectionDriftAndInvalidDeletionSemantics()
            throws Exception {
        KeyPair keyPair = keyPair();
        ScenarioRehearsalRetentionVerifier verifier =
                new ScenarioRehearsalRetentionVerifier();

        ObjectNode drifted = signedState(keyPair);
        drifted.put("requestId", "different-request");
        assertThat(verifier.verify(
                drifted, key(keyPair)).reasonCode())
                .isEqualTo("SCENARIO_RETENTION_PROOF_INVALID");

        ObjectNode heldPurge = signedState(keyPair);
        heldPurge.withArray("activeHoldIds")
                .add("legal-hold-a");
        assertThat(verifier.verify(
                heldPurge, key(keyPair)).reasonCode())
                .isEqualTo("SCENARIO_RETENTION_SCHEMA_INVALID");
    }

    @Test
    void rejectsAKeyThatWasNotActiveAtSigningTime()
            throws Exception {
        KeyPair keyPair = keyPair();
        EvidenceVerificationKey future = new EvidenceVerificationKey(
                TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1,
                "scenario-retention-key-1",
                "Ed25519",
                Base64.getEncoder().encodeToString(
                        keyPair.getPublic().getEncoded()),
                SIGNED_AT.plusSeconds(3600),
                "ACTIVE",
                "test");

        assertThat(new ScenarioRehearsalRetentionVerifier()
                .verify(signedState(keyPair), future).outcome())
                .isEqualTo(
                        ScenarioRehearsalRetentionVerifier
                                .Outcome.POLICY_REJECTED);
    }

    private static ObjectNode signedState(KeyPair keyPair)
            throws Exception {
        ObjectNode event = event();
        String fingerprint =
                ScenarioRehearsalRetentionVerifier
                        .eventFingerprint(event);
        ObjectNode seal = event.withObject("evidenceSeal");
        seal.put("materialFingerprint", fingerprint);
        seal.put("signature", sign(keyPair, fingerprint));

        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_RETENTION_STATE_V1);
        value.set("scope", scope());
        value.put("runId", runId());
        value.put("requestId", "scenario-request-1");
        value.put("evidenceBundleFingerprint", fingerprint('a'));
        value.put("status", "PURGED");
        value.put("revision", 2);
        value.put("retainUntil", "2026-07-24T08:00:00Z");
        value.putArray("activeHoldIds");
        value.put("updatedAt", "2026-07-25T08:00:00Z");
        value.set("latestEvent", event);
        return value;
    }

    private static ObjectNode event() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_RETENTION_EVENT_V1);
        value.put("eventId", "event-2");
        value.put("commandId", "purge-command-1");
        value.set("scope", scope());
        value.put("requestId", "scenario-request-1");
        value.put("runId", runId());
        value.put("revision", 2);
        value.put("type", "PURGED");
        value.put("retainUntil", "2026-07-24T08:00:00Z");
        value.put("occurredAt", "2026-07-25T08:00:00Z");
        value.put("actorId", "governance-admin");
        value.put("reasonCode",
                "RG.MIRROR.REHEARSAL.RETENTION_EXPIRED");
        value.put("holdId", "");
        value.put("evidenceBundleFingerprint", fingerprint('a'));
        value.put("previousEventFingerprint", fingerprint('b'));
        value.put("deletedCaseProgressCount", 3);
        value.put("childEvidenceDisposition", "RETAINED");
        ObjectNode seal = value.putObject("evidenceSeal");
        seal.put("schemaVersion", "bloge.visualRunEvidenceSeal.v1");
        seal.put("materialFingerprint", fingerprint('c'));
        seal.put("algorithm", "Ed25519");
        seal.put("keyId", "scenario-retention-key-1");
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

    private static EvidenceVerificationKey key(KeyPair keyPair) {
        return new EvidenceVerificationKey(
                TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1,
                "scenario-retention-key-1",
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

    private static String runId() {
        return "scenario-" + "9".repeat(64);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
