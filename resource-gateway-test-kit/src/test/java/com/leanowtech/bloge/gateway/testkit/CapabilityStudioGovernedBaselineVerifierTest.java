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
    void packagesBothGovernedBaselineSchemaVersions() {
        assertThat(getClass().getResource(CapabilityStudioSchemaSupport.GOVERNED_BASELINE_V1_RESOURCE))
                .isNotNull();
        assertThat(getClass().getResource(CapabilityStudioSchemaSupport.GOVERNED_BASELINE_V2_RESOURCE))
                .isNotNull();
    }

    @Test
    void verifiesTheCompleteNineByThreePassedReceipt() {
        CapabilityStudioGovernedBaselineVerifier.VerificationResult result =
                VERIFIER.verify(passedProjection());

        assertThat(result.verified()).withFailMessage("verification result: %s", result).isTrue();
        assertThat(result.checks()).contains(
                "SCHEMA", "UNIQUE_SUITE_RUN_IDS", "UNIQUE_CASE_IDS", "UNIQUE_CHILD_RUN_IDS",
                "CASE_ORACLES", "CASE_ASSERTIONS", "FIXTURE_CONTROLS",
                "SEMANTIC_RESULT_STABILITY", "CASE_PROOFS", "LIMITATIONS");
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
        String firstRunId = projection.withArray("cases").get(0).withArray("rounds")
                .get(0).path("runId").asText();
        ((ObjectNode) projection.withArray("cases").get(1).withArray("rounds").get(0))
                .put("runId", firstRunId);

        assertFailure(projection, CapabilityStudioGovernedBaselineVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_CASE_ROUND_INVALID");
    }

    @Test
    void rejectsTamperedSemanticResultFingerprint() {
        ObjectNode projection = passedProjection();
        ((ObjectNode) projection.withArray("cases").get(0).withArray("rounds").get(1))
                .put("semanticResultFingerprint", fingerprint('f'));

        assertFailure(projection, CapabilityStudioGovernedBaselineVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_SEMANTIC_RESULT_FINGERPRINT_DRIFT");
    }

    @Test
    void rejectsTamperedAssertionsAtTheSchemaBoundary() {
        ObjectNode projection = passedProjection();
        ((ObjectNode) projection.withArray("cases").get(0)).put("assertionsPassed", 2);

        assertFailure(projection, CapabilityStudioGovernedBaselineVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_SCHEMA_INVALID");
    }

    @Test
    void rejectsTamperedHighRiskProofAtTheSchemaBoundary() {
        ObjectNode projection = passedProjection();
        ((ArrayNode) projection.withArray("cases").get(2).withArray("proofs"))
                .set(4, JSON.getNodeFactory().textNode("BUSINESS_ASSERTION_PASSED"));

        assertFailure(projection, CapabilityStudioGovernedBaselineVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_SCHEMA_INVALID");
    }

    @Test
    void rejectsTamperedEvidenceClassAtTheSchemaBoundary() {
        ObjectNode projection = passedProjection();
        projection.put("evidenceClass", "CERTIFIABLE");

        assertFailure(projection, CapabilityStudioGovernedBaselineVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_SCHEMA_INVALID");
    }

    @Test
    void rejectsFabricatedFailedClosedCounts() {
        ObjectNode projection = failedClosedProjection();
        projection.put("suiteRunCount", 1);

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
        assertThat(result.failureKind()).withFailMessage("verification result: %s", result)
                .isEqualTo(kind);
        assertThat(result.errorCode()).withFailMessage("verification result: %s", result)
                .isEqualTo(errorCode);
    }

    private static ObjectNode passedProjection() {
        ObjectNode result = JSON.createObjectNode()
                .put("schemaVersion", "resource-gateway.capability-studio.governed-baseline.v2")
                .put("evidenceKind", "DEVELOPMENT_TEST_OWNED")
                .put("baselineId", "capability-studio-governed-9x3-v1")
                .put("status", "PASSED")
                .put("verificationScope", "GOVERNED_SUITE_ASSERTIONS_AND_BUSINESS_ORACLES")
                .put("releaseGateStatus", "NO_GO")
                .put("evidenceClass", "EXPLORATORY")
                .put("caseCount", 9)
                .put("roundCount", 3)
                .put("suiteRunCount", 3)
                .put("childRunCount", 27)
                .put("oraclePassCount", 9)
                .put("businessCheckCount", 27)
                .put("businessCheckPassCount", 27)
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
            cases.add(caseNode(caseIndex));
        }
        addLimitationsAndDiagnostics(result);
        return result;
    }

    private static ObjectNode failedClosedProjection() {
        ObjectNode result = JSON.createObjectNode()
                .put("schemaVersion", "resource-gateway.capability-studio.governed-baseline.v2")
                .put("evidenceKind", "DEVELOPMENT_TEST_OWNED")
                .put("baselineId", "capability-studio-governed-9x3-v1")
                .put("status", "FAILED_CLOSED")
                .put("verificationScope", "GOVERNED_SUITE_ASSERTIONS_AND_BUSINESS_ORACLES")
                .put("releaseGateStatus", "NO_GO")
                .putNull("evidenceClass")
                .put("caseCount", 9)
                .put("roundCount", 3)
                .put("suiteRunCount", 0)
                .put("childRunCount", 0)
                .put("oraclePassCount", 0)
                .put("businessCheckCount", 0)
                .put("businessCheckPassCount", 0)
                .put("realExternalCallCount", 0)
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

    private static ObjectNode caseNode(int caseIndex) {
        String caseId = CapabilityStudioGovernedBaselineVerifier.CANONICAL_CASE_IDS.get(caseIndex);
        ObjectNode result = JSON.createObjectNode()
                .put("caseId", caseId)
                .put("oracleId", "oracle-" + caseId.substring("case-".length()))
                .put("oracleStatus", "PASS")
                .put("semanticResultFingerprint", fingerprint('a'));
        result.put("assertionsEvaluated", 3).put("assertionsPassed", 3)
                .put("fixtureControlsEvaluated", 3).put("fixtureControlsSatisfied", 3);
        ArrayNode proofs = result.putArray("proofs");
        proofs.add("BUSINESS_ASSERTION_PASSED")
                .add("SEMANTIC_RESULT_STABLE")
                .add("FIXTURE_CONTROL_SATISFIED")
                .add("ZERO_REAL_EXTERNAL_CALLS");
        if ("case-compensation-history-timeout".equals(caseId)) {
            proofs.add("TIMEOUT_FALLBACK_CONFIRMED");
        } else if ("case-duplicate-cancellation".equals(caseId)) {
            proofs.add("DUPLICATE_IDEMPOTENCY_CONFIRMED");
        } else if ("case-forbidden-write-effect".equals(caseId)) {
            proofs.add("FORBIDDEN_WRITE_EFFECT_ABSENT");
        }
        ArrayNode rounds = result.putArray("rounds");
        for (int round = 1; round <= 3; round++) {
            rounds.add(caseRound(caseIndex, round, fingerprint('a')));
        }
        return result;
    }

    private static ObjectNode caseRound(int caseIndex, int round, String semanticFingerprint) {
        return JSON.createObjectNode()
                .put("round", round)
                .put("runId", "child-run-" + caseIndex + "-" + round)
                .put("status", "PASSED")
                .put("fixtureBundleId", "fixture-bundle-" + caseIndex)
                .put("fixtureRevision", 1)
                .put("fixtureFingerprint", fingerprint('f'))
                .put("evidenceFingerprint", fingerprint((char) ('a' + round)))
                .put("semanticResultFingerprint", semanticFingerprint)
                .put("assertionsEvaluated", 1)
                .put("assertionsPassed", 1)
                .put("fixtureControlsEvaluated", 1)
                .put("fixtureControlsSatisfied", 1);
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
