package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioRehearsalRemediationVerifierTest {
    private final ScenarioRehearsalRemediationVerifier verifier =
            new ScenarioRehearsalRemediationVerifier();

    @Test
    void verifiesCompleteSubmittedLineageWithoutMutatingInput() {
        ObjectNode lineage =
                ScenarioRehearsalRemediationTestFixtures
                        .submitted().lineage().deepCopy();
        ObjectNode before = lineage.deepCopy();

        ScenarioRehearsalRemediationVerifier
                .VerificationResult result =
                verifier.verify(lineage);

        assertThat(result.verified()).isTrue();
        assertThat(result.remediationId()).isEqualTo(
                ScenarioRehearsalRemediationTestFixtures
                        .REMEDIATION_ID);
        assertThat(result.predecessorJobId()).isEqualTo(
                ScenarioRehearsalRemediationTestFixtures
                        .PREDECESSOR_ID);
        assertThat(result.successorJobId()).isEqualTo(
                ScenarioRehearsalRemediationTestFixtures
                        .SUCCESSOR_ID);
        assertThat(result.state()).isEqualTo("SUBMITTED");
        assertThat(lineage).isEqualTo(before);
    }

    @Test
    void rejectsFactProjectionAndWholeLineageTampering() {
        ObjectNode approvalTamper =
                ScenarioRehearsalRemediationTestFixtures
                        .submitted().lineage().deepCopy();
        ((ObjectNode) approvalTamper.path(
                "approvals").get(1))
                .put("actorId", "owner-a");
        ObjectNode stateTamper =
                ScenarioRehearsalRemediationTestFixtures
                        .submitted().lineage().deepCopy();
        stateTamper.put("state", "APPROVED");
        ObjectNode lineageTamper =
                ScenarioRehearsalRemediationTestFixtures
                        .submitted().lineage().deepCopy();
        lineageTamper.put(
                "lineageFingerprint",
                "sha256:" + "0".repeat(64));
        ObjectNode selfConsistentExpiredApproval =
                ScenarioRehearsalRemediationTestFixtures
                        .submitted().lineage().deepCopy();
        ObjectNode reviewer = (ObjectNode)
                selfConsistentExpiredApproval.path(
                        "approvals").get(1);
        reviewer.put(
                "decidedAt",
                "2026-07-26T10:00:01Z");
        reseal(reviewer, "approvalFingerprint");
        String approvalHead =
                reviewer.path("approvalFingerprint")
                        .asText();
        ObjectNode receipt = (ObjectNode)
                selfConsistentExpiredApproval.path(
                        "receipt");
        receipt.put(
                "approvalHeadFingerprint",
                approvalHead);
        reseal(receipt, "receiptFingerprint");
        selfConsistentExpiredApproval.put(
                "approvalHeadFingerprint",
                approvalHead);
        reseal(
                selfConsistentExpiredApproval,
                "lineageFingerprint");

        assertThat(verifier.verify(
                approvalTamper).verified()).isFalse();
        assertThat(verifier.verify(
                stateTamper).reasonCode())
                .isEqualTo(
                        "SCENARIO_REMEDIATION_LINEAGE_SCHEMA_INVALID");
        assertThat(verifier.verify(
                lineageTamper).reasonCode())
                .isEqualTo(
                        "SCENARIO_REMEDIATION_LINEAGE_FINGERPRINT_INVALID");
        assertThat(verifier.verify(
                selfConsistentExpiredApproval)
                .reasonCode())
                .isEqualTo(
                        "SCENARIO_REMEDIATION_APPROVAL_CHAIN_INVALID");
    }

    private static void reseal(
            ObjectNode value,
            String field) {
        value.put(field, "");
        value.put(
                field,
                EvidenceVerificationSupport.sha256(
                        value));
    }
}
