package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoritativeOutcomeContinuousAssessmentRemediationVerifierTest {
    private static final ObjectMapper JSON =
            new ObjectMapper().findAndRegisterModules();
    private static final String PREVIOUS_EVENT =
            "sha256:" + "b".repeat(64);
    private static final String ACTOR =
            "sha256:" + "c".repeat(64);
    private final AuthoritativeOutcomeContinuousAssessmentRemediationVerifier
            verifier =
            new
                    AuthoritativeOutcomeContinuousAssessmentRemediationVerifier();

    @Test
    void verifiesActorBoundCommandAndExactRecoveryDelta() {
        ObjectNode receipt = receipt();

        var result = verifier.verify(receipt);

        assertThat(result.verified()).isTrue();
        assertThat(result.projectionId())
                .isEqualTo("refund-completeness");
        assertThat(result.commandId())
                .isEqualTo("remediation-1");
        assertThat(result.remediationGeneration())
                .isEqualTo(1);
        assertThat(result.currentProjectionFingerprint())
                .isEqualTo(receipt.path("lifecycleEvent")
                        .path("projection")
                        .path("recordFingerprint")
                        .asText());
    }

    @Test
    void rejectsReaddressedAttemptAndEvidenceMutation() {
        ObjectNode receipt = receipt();
        ObjectNode current =
                (ObjectNode) receipt.path(
                        "lifecycleEvent")
                        .path("projection");
        current.put("attemptCount", 2);
        AuthoritativeOutcomeContinuousAssessmentVerifierTest
                .seal(current);
        readdressEventAndReceipt(receipt);

        var result = verifier.verify(receipt);

        assertThat(result.verified()).isFalse();
        assertThat(result.reasonCode())
                .isEqualTo(
                        "OUTCOME_CONTINUOUS_ASSESSMENT_REMEDIATION_TRANSITION_INVALID");
    }

    @Test
    void rejectsCommandActorAndReceiptFingerprintTampering() {
        ObjectNode actorTampered = receipt();
        ((ObjectNode) actorTampered.path(
                "lifecycleEvent"))
                .put(
                        "actorFingerprint",
                        "sha256:" + "d".repeat(64));
        readdressEvent(actorTampered);
        sealReceiptOnly(actorTampered);

        assertThat(verifier.verify(actorTampered)
                .reasonCode()).isEqualTo(
                "OUTCOME_CONTINUOUS_ASSESSMENT_REMEDIATION_COMMAND_FINGERPRINT_INVALID");

        ObjectNode receiptTampered = receipt();
        receiptTampered.put(
                "remediationGeneration", 2);
        assertThat(verifier.verify(receiptTampered)
                .reasonCode()).isEqualTo(
                "OUTCOME_CONTINUOUS_ASSESSMENT_REMEDIATION_RECEIPT_FINGERPRINT_INVALID");
    }

    @Test
    void rejectsUnknownFieldsBeforeSemanticVerification() {
        ObjectNode receipt = receipt();
        receipt.put("operatorComment", "not allowed");

        assertThat(verifier.verify(receipt)
                .verified()).isFalse();
    }

    private static ObjectNode receipt() {
        ObjectNode previous =
                AuthoritativeOutcomeContinuousAssessmentVerifierTest
                        .baseProjection();
        previous.put("status", "QUARANTINED");
        previous.putNull("lastAssessmentRef");
        previous.put(
                "observationSetFingerprint", "");
        previous.put(
                "dispositionSetFingerprint", "");
        previous.put(
                "currentThrough",
                "1970-01-01T00:00:00Z");
        previous.put(
                "freshUntil",
                "1970-01-01T00:00:00Z");
        previous.put("attemptCount", 1);
        previous.put(
                "consecutiveFailures", 1);
        previous.put(
                "nextEligibleAt",
                "2026-07-27T00:00:02Z");
        previous.put("leaseEpoch", 1);
        previous.put(
                "failureCode",
                "DEPENDENCY_FAILED");
        previous.put(
                "updatedAt",
                "2026-07-27T00:00:02Z");
        previous.put(
                "terminalAt",
                "2026-07-27T00:00:02Z");
        AuthoritativeOutcomeContinuousAssessmentVerifierTest
                .seal(previous);

        ObjectNode command =
                JSON.createObjectNode();
        command.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_REMEDIATION_REQUEST_V1);
        command.put(
                "commandId", "remediation-1");
        command.put(
                "expectedProjectionFingerprint",
                previous.path(
                        "recordFingerprint")
                        .asText());
        command.put(
                "expectedLifecycleHeadOrdinal", 3);
        command.put(
                "expectedLifecycleHeadFingerprint",
                PREVIOUS_EVENT);
        command.put(
                "reasonCode",
                "DEPENDENCY_REPAIRED");

        ObjectNode current =
                previous.deepCopy();
        current.put("status", "QUEUED");
        current.put(
                "consecutiveFailures", 0);
        current.put(
                "nextEligibleAt",
                "2026-07-27T00:00:03Z");
        current.put("failureCode", "");
        current.put(
                "updatedAt",
                "2026-07-27T00:00:03Z");
        current.putNull("terminalAt");
        AuthoritativeOutcomeContinuousAssessmentVerifierTest
                .seal(current);
        ObjectNode event =
                AuthoritativeOutcomeContinuousAssessmentLifecycleVerifierTest
                        .event(
                                4,
                                "REMEDIATION_ACCEPTED",
                                ACTOR,
                                current,
                                PREVIOUS_EVENT);

        ObjectNode receipt =
                JSON.createObjectNode();
        receipt.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_REMEDIATION_RECEIPT_V1);
        receipt.put("receiptFingerprint", "");
        receipt.put("commandFingerprint", "");
        receipt.set(
                "scope",
                previous.path("scope")
                        .deepCopy());
        receipt.put(
                "projectionId",
                "refund-completeness");
        receipt.put(
                "remediationGeneration", 1);
        receipt.set("command", command);
        receipt.set(
                "previousProjection",
                previous);
        receipt.set(
                "lifecycleEvent", event);
        seal(receipt);
        return receipt;
    }

    private static void readdressEventAndReceipt(
            ObjectNode receipt) {
        readdressEvent(receipt);
        seal(receipt);
    }

    private static void readdressEvent(
            ObjectNode receipt) {
        ObjectNode event =
                (ObjectNode) receipt.path(
                        "lifecycleEvent");
        event.put("eventFingerprint", "");
        event.put(
                "eventFingerprint",
                EvidenceVerificationSupport
                        .sha256Bounded(
                                event,
                                AuthoritativeOutcomeContinuousAssessmentLifecycleVerifier
                                        .MAXIMUM_EVENT_BYTES));
    }

    private static void seal(ObjectNode receipt) {
        ObjectNode binding =
                JSON.createObjectNode();
        binding.put(
                "schemaVersion",
                AuthoritativeOutcomeContinuousAssessmentRemediationVerifier
                        .COMMAND_BINDING_SCHEMA_VERSION);
        binding.set(
                "scope",
                receipt.path("scope")
                        .deepCopy());
        binding.put(
                "projectionId",
                receipt.path(
                        "projectionId")
                        .asText());
        binding.put(
                "actorFingerprint",
                receipt.path("lifecycleEvent")
                        .path("actorFingerprint")
                        .asText());
        binding.set(
                "command",
                receipt.path("command")
                        .deepCopy());
        receipt.put(
                "commandFingerprint",
                EvidenceVerificationSupport
                        .sha256Bounded(
                                binding,
                                AuthoritativeOutcomeContinuousAssessmentRemediationVerifier
                                        .MAXIMUM_COMMAND_BINDING_BYTES));
        sealReceiptOnly(receipt);
    }

    private static void sealReceiptOnly(
            ObjectNode receipt) {
        receipt.put("receiptFingerprint", "");
        receipt.put(
                "receiptFingerprint",
                EvidenceVerificationSupport
                        .sha256Bounded(
                                receipt,
                                AuthoritativeOutcomeContinuousAssessmentRemediationVerifier
                                        .MAXIMUM_RECEIPT_BYTES));
    }
}
