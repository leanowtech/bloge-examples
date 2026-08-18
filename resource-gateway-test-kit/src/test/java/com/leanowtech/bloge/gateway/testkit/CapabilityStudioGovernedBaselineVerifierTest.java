package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioGovernedBaselineVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CapabilityStudioGovernedBaselineVerifier VERIFIER =
            new CapabilityStudioGovernedBaselineVerifier();

    @Test
    void packagesTheGovernedBaselineSchema() {
        assertThat(getClass().getResource(CapabilityStudioSchemaSupport.GOVERNED_BASELINE_RESOURCE))
                .isNotNull();
    }

    @Test
    void verifiesTheCompleteNineByThreePassedReceipt() {
        CapabilityStudioGovernedBaselineVerifier.VerificationResult result =
                VERIFIER.verify(passedProjection());

        assertThat(result.verified()).isTrue();
        assertThat(result.checks()).contains(
                "SCHEMA",
                "UNIQUE_SUITE_RUN_IDS",
                "UNIQUE_CASE_IDS",
                "UNIQUE_CHILD_RUN_IDS",
                "CASE_ROUND_COVERAGE",
                "PASSED_STATUS_MATRIX",
                "LIMITATIONS");
    }

    @Test
    void verifiesAFailedClosedReceiptOnlyWhenItHasNoEvidence() {
        CapabilityStudioGovernedBaselineVerifier.VerificationResult result =
                VERIFIER.verify(failedClosedProjection());

        assertThat(result.verified()).isTrue();
        assertThat(result.checks()).containsExactlyInAnyOrder(
                "SCHEMA", "BASELINE_IDENTITY", "FAILED_CLOSED_NO_EVIDENCE", "LIMITATIONS");
    }

    @Test
    void rejectsUnknownFieldsAtTheStrictSchemaBoundary() {
        ObjectNode projection = passedProjection();
        projection.put("payload", "must-not-cross-the-boundary");

        assertFailure(projection, CapabilityStudioGovernedBaselineVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_SCHEMA_INVALID");
    }

    @Test
    void rejectsDuplicateSuiteRunIds() {
        ObjectNode projection = passedProjection();
        ObjectNode secondRound = (ObjectNode) projection.withArray("rounds").get(1);
        secondRound.put("suiteRunId", projection.withArray("rounds").get(0)
                .path("suiteRunId").asText());

        assertFailure(projection, CapabilityStudioGovernedBaselineVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_SUITE_RUN_INVALID");
    }

    @Test
    void rejectsDuplicateChildRunIdsAcrossCases() {
        ObjectNode projection = passedProjection();
        ObjectNode firstCase = (ObjectNode) projection.withArray("cases").get(0);
        String firstRunId = firstCase.withArray("rounds").get(0).path("runId").asText();
        ObjectNode secondCase = (ObjectNode) projection.withArray("cases").get(1);
        ((ObjectNode) secondCase.withArray("rounds").get(0)).put("runId", firstRunId);

        assertFailure(projection, CapabilityStudioGovernedBaselineVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_CASE_ROUND_INVALID");
    }

    @Test
    void rejectsACaseRoundThatIsNotOneTwoOrThreeInOrder() {
        ObjectNode projection = passedProjection();
        ObjectNode firstCase = (ObjectNode) projection.withArray("cases").get(0);
        ((ObjectNode) firstCase.withArray("rounds").get(2)).put("round", 2);

        assertFailure(projection, CapabilityStudioGovernedBaselineVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_CASE_ROUND_INVALID");
    }

    @Test
    void rejectsNonPassedStatusesBeforeTheyCanBecomeEvidence() {
        ObjectNode projection = passedProjection();
        ObjectNode firstCase = (ObjectNode) projection.withArray("cases").get(0);
        ((ObjectNode) firstCase.withArray("rounds").get(0)).put("status", "FAILED");

        assertFailure(projection, CapabilityStudioGovernedBaselineVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_SCHEMA_INVALID");
    }

    @Test
    void rejectsNonZeroRealExternalCalls() {
        ObjectNode projection = passedProjection();
        projection.put("realExternalCallCount", 1);

        assertFailure(projection, CapabilityStudioGovernedBaselineVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_SCHEMA_INVALID");
    }

    @Test
    void rejectsTamperedLimitations() {
        ObjectNode projection = passedProjection();
        projection.withArray("limitations").set(0, JSON.getNodeFactory().textNode("CLAIMED_SIGNOFF"));

        assertFailure(projection, CapabilityStudioGovernedBaselineVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_SCHEMA_INVALID");
    }

    @Test
    void rejectsFailedClosedReceiptsThatContainAClaimedFingerprint() {
        ObjectNode projection = failedClosedProjection();
        projection.put("compilationFingerprint", fingerprint('f'));

        assertFailure(projection, CapabilityStudioGovernedBaselineVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_SCHEMA_INVALID");
    }

    @Test
    void rejectsFailedClosedReceiptsThatContainRunsOrCases() {
        ObjectNode projection = failedClosedProjection();
        projection.putArray("rounds").add(round(1));

        assertFailure(projection, CapabilityStudioGovernedBaselineVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_SCHEMA_INVALID");
    }

    @Test
    void rejectsFailedClosedReceiptsWithoutDiagnostics() {
        ObjectNode projection = failedClosedProjection();
        projection.putArray("diagnostics");

        assertFailure(projection, CapabilityStudioGovernedBaselineVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_SCHEMA_INVALID");
    }

    @Test
    void returnsStablePayloadFreeErrorsForMalformedWireInput() {
        CapabilityStudioGovernedBaselineVerifier.VerificationResult result =
                VERIFIER.verify("{not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioGovernedBaselineVerifier.FailureKind.SCHEMA);
        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_INVALID_JSON");
        assertThat(result.toString()).doesNotContain("not-json", "payload", "fixture");
    }

    private static void assertFailure(
            JsonNode projection,
            CapabilityStudioGovernedBaselineVerifier.FailureKind kind,
            String errorCode) {
        CapabilityStudioGovernedBaselineVerifier.VerificationResult result =
                VERIFIER.verify(projection);
        assertThat(result.failureKind()).isEqualTo(kind);
        assertThat(result.errorCode()).isEqualTo(errorCode);
    }

    private static ObjectNode passedProjection() {
        ObjectNode result = JSON.createObjectNode()
                .put("schemaVersion", "resource-gateway.capability-studio.governed-baseline.v1")
                .put("evidenceKind", "DEVELOPMENT_TEST_OWNED")
                .put("baselineId", "capability-studio-governed-9x3-v1")
                .put("status", "PASSED")
                .put("verificationScope", "GOVERNED_SUITE_ASSERTIONS")
                .put("releaseGateStatus", "NO_GO")
                .put("caseCount", 9)
                .put("roundCount", 3)
                .put("suiteRunCount", 3)
                .put("childRunCount", 27)
                .put("realExternalCallCount", 0)
                .put("compilationFingerprint", fingerprint('a'))
                .put("sourceMapFingerprint", fingerprint('b'))
                .put("provenanceFingerprint", fingerprint('c'));
        ObjectNode suiteRef = JSON.createObjectNode()
                .put("kind", "TEST_SUITE")
                .put("id", "governed-suite")
                .put("revision", 1)
                .put("fingerprint", fingerprint('e'));
        ObjectNode publication = JSON.createObjectNode()
                .put("receiptFingerprint", fingerprint('d'))
                .put("fixtureCount", 9);
        publication.set("suiteRef", suiteRef);
        result.set("publication", publication);
        ArrayNode rounds = result.putArray("rounds");
        for (int round = 1; round <= 3; round++) {
            rounds.add(round(round));
        }
        ArrayNode cases = result.putArray("cases");
        for (int caseIndex = 0;
             caseIndex < CapabilityStudioGovernedBaselineVerifier.CANONICAL_CASE_IDS.size();
             caseIndex++) {
            ObjectNode caseNode = cases.addObject()
                    .put("caseId", CapabilityStudioGovernedBaselineVerifier.CANONICAL_CASE_IDS
                            .get(caseIndex));
            ArrayNode caseRounds = caseNode.putArray("rounds");
            for (int round = 1; round <= 3; round++) {
                caseRounds.add(caseRound(caseIndex, round));
            }
        }
        addLimitationsAndDiagnostics(result);
        return result;
    }

    private static ObjectNode failedClosedProjection() {
        ObjectNode result = JSON.createObjectNode()
                .put("schemaVersion", "resource-gateway.capability-studio.governed-baseline.v1")
                .put("evidenceKind", "DEVELOPMENT_TEST_OWNED")
                .put("baselineId", "capability-studio-governed-9x3-v1")
                .put("status", "FAILED_CLOSED")
                .put("verificationScope", "GOVERNED_SUITE_ASSERTIONS")
                .put("releaseGateStatus", "NO_GO")
                .put("caseCount", 9)
                .put("roundCount", 3)
                .put("suiteRunCount", 0)
                .put("childRunCount", 0)
                .putNull("realExternalCallCount")
                .putNull("compilationFingerprint")
                .putNull("sourceMapFingerprint")
                .putNull("provenanceFingerprint")
                .putNull("publication");
        result.putArray("rounds");
        result.putArray("cases");
        addLimitationsAndDiagnostics(result);
        result.withArray("diagnostics").add("GOVERNED_BASELINE_EXECUTION_FAILED");
        return result;
    }

    private static ObjectNode round(int round) {
        return JSON.createObjectNode()
                .put("round", round)
                .put("suiteRunId", "suite-run-" + round)
                .put("evidenceFingerprint", fingerprint('f'))
                .put("status", "PASSED")
                .put("childRunCount", 9);
    }

    private static ObjectNode caseRound(int caseIndex, int round) {
        return JSON.createObjectNode()
                .put("round", round)
                .put("runId", "child-run-" + caseIndex + "-" + round)
                .put("status", "PASSED")
                .put("fixtureBundleId", "fixture-bundle-" + caseIndex)
                .put("fixtureRevision", 1)
                .put("fixtureFingerprint", fingerprint('f'));
    }

    private static void addLimitationsAndDiagnostics(ObjectNode result) {
        ArrayNode limitations = result.putArray("limitations");
        CapabilityStudioGovernedBaselineVerifier.LIMITATIONS.forEach(limitations::add);
        result.putArray("diagnostics");
    }

    private static String fingerprint(char fill) {
        return "sha256:" + String.valueOf(fill).repeat(64);
    }
}
