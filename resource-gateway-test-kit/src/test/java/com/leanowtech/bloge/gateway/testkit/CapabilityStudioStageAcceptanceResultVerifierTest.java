package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioStageAcceptanceResultVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CapabilityStudioStageAcceptanceResultVerifier VERIFIER =
            new CapabilityStudioStageAcceptanceResultVerifier();
    private static final String STARTED = "2026-01-01T00:00:00Z";
    private static final String COMPLETED = "2026-01-01T00:05:00Z";

    @Test
    void verifiesACompleteStageExitPass() {
        CapabilityStudioStageAcceptanceResultVerifier.VerificationResult result =
                VERIFIER.verify(validStagePass());

        assertThat(result.verified()).isTrue();
        assertThat(result.checks()).containsExactly(
                "SCHEMA", "PAYLOAD_FREE", "PRECONDITION_CLOSURE", "STATUS_SEMANTICS",
                "STAGE_KIND_POLICY", "TIME_ORDER", "MATRIX_CLOSURE", "AUTOMATION_COMMANDS",
                "OBSERVATIONS", "EVIDENCE_AVAILABILITY", "OPEN_BLOCKER_GATE", "OWNER_SIGN_OFF");
        assertThat(result.errorCode()).isNull();
    }

    @Test
    void verifiesADevelopmentLedgerPartialWithSubEvidence() {
        ObjectNode result = validStagePass();
        result.put("resultKind", "DEVELOPMENT_LEDGER");
        result.put("status", "PARTIAL");
        ObjectNode notRun = result.withArray("executedMatrix").addObject();
        notRun.put("matrixId", "matrix-case-02");
        notRun.put("caseId", "CASE-02");
        notRun.put("executionStatus", "NOT_RUN");
        notRun.putNull("startedAt");
        notRun.putNull("completedAt");
        notRun.putArray("evidenceRefs");

        CapabilityStudioStageAcceptanceResultVerifier.VerificationResult verification =
                VERIFIER.verify(result);

        assertThat(verification.verified()).isTrue();
    }

    @Test
    void rejectsUnknownFieldsAtTheStrictSchemaBoundary() {
        ObjectNode result = validStagePass();
        result.put("unexpected", true);

        assertFailure(result, CapabilityStudioStageAcceptanceResultVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_SCHEMA_INVALID");
    }

    @Test
    void rejectsPayloadAndSensitiveFieldNamesBeforeSchemaProcessing() {
        ObjectNode result = validStagePass();
        result.putObject("payload").put("orderId", "hidden");

        assertFailure(result, CapabilityStudioStageAcceptanceResultVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_SENSITIVE_FIELD");
    }

    @Test
    void rejectsDuplicatePreconditions() {
        ObjectNode result = validStagePass();
        ((ObjectNode) result.withArray("preconditions").get(1))
                .put("preconditionId", "AC-PRE-01");

        assertFailure(result, CapabilityStudioStageAcceptanceResultVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PRECONDITION_DUPLICATE");
    }

    @Test
    void rejectsMissingPreconditions() {
        ObjectNode result = validStagePass();
        ((ObjectNode) result.withArray("preconditions").get(4))
                .put("preconditionId", "AC-PRE-01");

        assertFailure(result, CapabilityStudioStageAcceptanceResultVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PRECONDITION_DUPLICATE");
    }

    @Test
    void rejectsPassWhenAnyPreconditionIsNotPassed() {
        ObjectNode result = validStagePass();
        ((ObjectNode) result.withArray("preconditions").get(0)).put("status", "NOT_RUN");

        assertFailure(result, CapabilityStudioStageAcceptanceResultVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PASS_PRECONDITION");
    }

    @Test
    void rejectsPassWhenEvidenceIsMissing() {
        ObjectNode result = validStagePass();
        ((ObjectNode) result.withArray("evidenceRefs").get(0)).put("status", "MISSING");

        assertFailure(result, CapabilityStudioStageAcceptanceResultVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PASS_EVIDENCE_INVALID");
    }

    @Test
    void rejectsPassWhenOwnerSignOffIsMissing() {
        ObjectNode result = validStagePass();
        result.putArray("signOffs");

        assertFailure(result, CapabilityStudioStageAcceptanceResultVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PASS_OWNER_SIGN_OFF");
    }

    @Test
    void rejectsPassWhenThereIsAnOpenP0OrP1Blocker() {
        ObjectNode result = validStagePass();
        result.withArray("openIssues").addObject()
                .put("issueId", "issue-p0")
                .put("severity", "P0")
                .put("status", "OPEN")
                .put("blocker", false)
                .put("summary", "release gate is not closed");

        assertFailure(result, CapabilityStudioStageAcceptanceResultVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PASS_OPEN_BLOCKER");
    }

    @Test
    void rejectsReverseTimeOrder() {
        ObjectNode result = validStagePass();
        result.put("startedAt", COMPLETED);
        result.put("completedAt", STARTED);

        assertFailure(result, CapabilityStudioStageAcceptanceResultVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PASS_TIME_INVALID");
    }

    @Test
    void rejectsPartialForStageExit() {
        ObjectNode result = validStagePass();
        result.put("status", "PARTIAL");

        assertFailure(result, CapabilityStudioStageAcceptanceResultVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PARTIAL_STAGE_EXIT");
    }

    @Test
    void rejectsNotRunWhenItContainsACompletedMatrix() {
        ObjectNode result = validStagePass();
        result.put("status", "NOT_RUN");
        result.putNull("completedAt");

        assertFailure(result, CapabilityStudioStageAcceptanceResultVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_NOT_RUN_HAS_COMPLETION");
    }

    @Test
    void rejectsBlockedWithoutBlockedOrNotRunPrecondition() {
        ObjectNode result = validStagePass();
        result.put("status", "BLOCKED");

        assertFailure(result, CapabilityStudioStageAcceptanceResultVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_BLOCKED_WITHOUT_PRECONDITION");
    }

    @Test
    void rejectsFailWithoutFailingPreconditionOrObservation() {
        ObjectNode result = validStagePass();
        result.put("status", "FAIL");

        assertFailure(result, CapabilityStudioStageAcceptanceResultVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_FAIL_WITHOUT_FAILURE");
    }

    @Test
    void acceptsBlockedAndFailWhenTheirRequiredFactsArePresent() {
        ObjectNode blocked = validStagePass();
        blocked.put("status", "BLOCKED");
        ((ObjectNode) blocked.withArray("preconditions").get(2)).put("status", "BLOCKED");
        assertThat(VERIFIER.verify(blocked).verified()).isTrue();

        ObjectNode failed = validStagePass();
        failed.put("status", "FAIL");
        ((ObjectNode) failed.withArray("observations").get(0)).put("status", "FAIL");
        assertThat(VERIFIER.verify(failed).verified()).isTrue();
    }

    @Test
    void returnsStablePayloadFreeResultForMalformedJson() {
        CapabilityStudioStageAcceptanceResultVerifier.VerificationResult result =
                VERIFIER.verify("{not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(result.verified()).isFalse();
        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioStageAcceptanceResultVerifier.FailureKind.SCHEMA);
        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_INVALID_JSON");
        assertThat(result.toString()).doesNotContain("not-json");
    }

    private static ObjectNode validStagePass() {
        ObjectNode result = JSON.createObjectNode();
        result.put("schemaVersion", "bloge.capabilityStudioStageAcceptanceResult.v1");
        result.put("resultId", "SAR-s0-ac-01-pass");
        result.put("revision", 1);
        result.put("resultKind", "STAGE_EXIT");
        result.put("contractId", "S0-AC-01");
        result.putObject("candidateBuildRef")
                .put("buildRef", "build:capability-studio-1")
                .put("revision", "rev-1")
                .put("artifactFingerprint", fingerprint('a'))
                .put("sourceCommit", "abcdef1234567");
        result.putObject("acceptanceBaselineRef")
                .put("exactRef", "baseline:S0:v1")
                .put("fingerprint", fingerprint('b'));
        result.putObject("goldenDemoPackRef")
                .put("exactRef", "demo-pack:golden:v1")
                .put("fingerprint", fingerprint('c'));
        result.put("environmentFingerprint", fingerprint('d'));
        result.putObject("executionIdentity")
                .put("actor", "acceptance-owner")
                .put("runId", "run:sar:001")
                .put("mode", "CI");
        result.put("startedAt", STARTED);
        result.put("completedAt", COMPLETED);

        ArrayNode matrix = result.putArray("executedMatrix");
        matrix.add(matrixEntry("matrix:case-01", "CASE-01", "evidence:matrix:01"));
        matrix.add(matrixEntry("matrix:case-02", "CASE-02", "evidence:matrix:02"));

        result.putArray("automationCommands").add("mvn -f resource-gateway-test-kit/pom.xml test");
        result.putArray("observations").add(observation("observation:baseline", "PASS"));
        result.putArray("evidenceRefs").add(evidence("evidence:acceptance", "AVAILABLE"));
        result.putArray("openIssues");
        result.putObject("owner").put("actor", "acceptance-owner").put("team", "quality");
        result.putArray("signOffs").add(
                JSON.createObjectNode()
                        .put("role", "OWNER")
                        .put("actor", "acceptance-owner")
                        .put("decision", "APPROVED")
                        .put("signature", "sig:owner:001")
                        .put("timestamp", COMPLETED));

        ArrayNode preconditions = result.putArray("preconditions");
        for (int i = 1; i <= 5; i++) {
            preconditions.add(JSON.createObjectNode()
                    .put("preconditionId", "AC-PRE-0" + i)
                    .put("status", "PASS")
                    .set("evidenceRefs", JSON.createArrayNode()
                            .add(evidence("evidence:pre:" + i, "AVAILABLE"))));
        }
        result.put("status", "PASS");
        return result;
    }

    private static ObjectNode matrixEntry(String matrixId, String caseId, String evidenceId) {
        return JSON.createObjectNode()
                .put("matrixId", matrixId)
                .put("caseId", caseId)
                .put("executionStatus", "EXECUTED")
                .put("startedAt", STARTED)
                .put("completedAt", COMPLETED)
                .set("evidenceRefs", JSON.createArrayNode().add(evidence(evidenceId, "AVAILABLE")));
    }

    private static ObjectNode observation(String observationId, String status) {
        return JSON.createObjectNode()
                .put("observationId", observationId)
                .put("status", status)
                .put("summary", "all required acceptance observations are recorded")
                .set("evidenceRefs", JSON.createArrayNode()
                        .add(evidence("evidence:" + observationId, "AVAILABLE")));
    }

    private static ObjectNode evidence(String evidenceId, String status) {
        return JSON.createObjectNode()
                .put("evidenceId", evidenceId)
                .put("exactRef", evidenceId)
                .put("fingerprint", fingerprint('e'))
                .put("status", status);
    }

    private static String fingerprint(char character) {
        return "sha256:" + String.valueOf(character).repeat(64);
    }

    private static void assertFailure(
            JsonNode value,
            CapabilityStudioStageAcceptanceResultVerifier.FailureKind failureKind,
            String errorCode) {
        CapabilityStudioStageAcceptanceResultVerifier.VerificationResult result =
                VERIFIER.verify(value);
        assertThat(result.verified()).isFalse();
        assertThat(result.failureKind()).isEqualTo(failureKind);
        assertThat(result.errorCode()).isEqualTo(errorCode);
    }
}
