package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteRunAssertionsTest {

    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void acceptsOnlyPassingCoveredAndEligibleSuiteEvidence() throws Exception {
        TestSuiteRun run = run("PASSED", "PASSED", "SATISFIED", "ELIGIBLE", "");

        assertThatCode(() -> TestSuiteRunAssertions.assertPassed(run)).doesNotThrowAnyException();
        assertThatCode(() -> TestSuiteRunAssertions.assertAllCasesPassed(run)).doesNotThrowAnyException();
        assertThatCode(() -> TestSuiteRunAssertions.assertCoverageSatisfied(run)).doesNotThrowAnyException();
        assertThatCode(() -> TestSuiteRunAssertions.assertPromotionEligible(run)).doesNotThrowAnyException();
    }

    @Test
    void reportsPayloadFreeCaseCoverageAndPromotionFailures() throws Exception {
        TestSuiteRun run = run("COMPLETED_WITH_FAILURES", "FAILED", "UNSATISFIED", "BLOCKED",
                "CASES_FAILED");

        assertThatThrownBy(() -> TestSuiteRunAssertions.assertPassed(run))
                .hasMessageContaining("COMPLETED_WITH_FAILURES")
                .hasMessageNotContaining("private diagnostic");
        assertThatThrownBy(() -> TestSuiteRunAssertions.assertAllCasesPassed(run))
                .hasMessageContaining("golden=FAILED");
        assertThatThrownBy(() -> TestSuiteRunAssertions.assertCoverageSatisfied(run))
                .hasMessageContaining("did not satisfy coverage");
        assertThatThrownBy(() -> TestSuiteRunAssertions.assertPromotionEligible(run))
                .hasMessageContaining("CASES_FAILED");
    }

    @Test
    void rejectsContradictoryPassingEvidenceAndNonMachineReasonCodes() throws Exception {
        ObjectNode contradictory = suiteResponse("PASSED", "PASSED", "SATISFIED", "ELIGIBLE", "");
        ((ObjectNode) contradictory.at("/evidence/caseResults/0"))
                .put("evidenceStatus", "ASSERTION_FAILED");

        assertThatThrownBy(() -> TestSuiteRun.from(contradictory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passing suite case");

        ObjectNode unsafeReason = suiteResponse("COMPLETED_WITH_FAILURES", "FAILED",
                "UNSATISFIED", "BLOCKED", "customer-secret=value");
        assertThatThrownBy(() -> TestSuiteRun.from(unsafeReason))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stable machine code");
    }

    @Test
    void rejectsSchemaIncompleteOrPayloadBearingProtocolStatesWithoutEchoingPayloads() throws Exception {
        ObjectNode incomplete = suiteResponse("PASSED", "PASSED", "SATISFIED", "ELIGIBLE", "");
        ((ObjectNode) incomplete.at("/evidence/coverage")).remove("completedCases");

        assertThatThrownBy(() -> TestSuiteRun.from(incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authoritative schema");

        String privateValue = "customer-secret-value";
        ObjectNode invalidStatus = suiteResponse("PASSED", "PASSED", "SATISFIED", "ELIGIBLE", "");
        ((ObjectNode) invalidStatus.path("evidence")).put("status", privateValue);

        assertThatThrownBy(() -> TestSuiteRun.from(invalidStatus))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(privateValue)
                .satisfies(failure -> {
                    Throwable cause = failure.getCause();
                    while (cause != null) {
                        assertThat(cause.getMessage()).doesNotContain(privateValue);
                        cause = cause.getCause();
                    }
                });
    }

    @Test
    void exposesTypedSemanticVerdictAndRejectsSilentV1Fallback() throws Exception {
        TestSuiteRun historical = run("PASSED", "PASSED", "SATISFIED", "ELIGIBLE", "");
        ObjectNode semanticResponse = suiteResponse(
                "PASSED", "PASSED", "SATISFIED", "ELIGIBLE", "");
        semanticResponse.put("schemaVersion", TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V3);
        ObjectNode evidence = (ObjectNode) semanticResponse.path("evidence");
        evidence.put("schemaVersion", TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V2);
        ObjectNode semantic = evidence.putObject("semanticCoverage");
        semantic.put("status", "SATISFIED");
        ObjectNode required = semantic.putArray("required").addObject();
        required.put("requirementId", "retry");
        required.put("kind", "RETRY");
        required.put("invocationSiteId", "/root/remote#PRIMARY");
        required.put("minimumAttempts", 2);
        ObjectNode observed = semantic.putArray("observed").addObject();
        observed.put("requirementId", "retry");
        observed.put("kind", "RETRY");
        observed.putArray("caseIds").add("golden");
        semantic.putArray("missingRequirementIds");
        semantic.putArray("unavailable");
        ObjectNode attestation = semanticResponse.putObject("attestation");
        attestation.put("schemaVersion", TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V2);
        attestation.put("signatureStatus", "UNSIGNED");
        attestation.put("scope", "CHECKPOINT");
        attestation.put("suiteRunId", "");
        attestation.putNull("suiteRef");
        attestation.put("requestFingerprint", "");
        attestation.put("aggregateEvidenceFingerprint", "");
        attestation.putArray("childEvidenceRefs");
        attestation.put("signedAt", "1970-01-01T00:00:00Z");
        attestation.put("keyId", "");
        attestation.put("algorithm", "");
        attestation.put("signature", "");
        attestation.put("independentlyVerifiable", false);

        TestSuiteRun semanticRun = TestSuiteRun.from(semanticResponse);

        assertThat(semanticRun.requireSemanticCoverage().status())
                .isEqualTo(TestSuiteRun.SemanticCoverageStatus.SATISFIED);
        assertThat(semanticRun.requireSemanticCoverage().observedRequirementIds())
                .containsExactly("retry");
        assertThatThrownBy(historical::requireSemanticCoverage)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SEMANTIC_COVERAGE_UNAVAILABLE");
    }

    private static TestSuiteRun run(String status, String caseStatus, String coverage,
                                    String promotion, String reason) throws Exception {
        return TestSuiteRun.from(suiteResponse(status, caseStatus, coverage, promotion, reason));
    }

    private static ObjectNode suiteResponse(String status, String caseStatus, String coverage,
                                            String promotion, String reason) throws Exception {
        String reasons = reason.isBlank() ? "[]" : "[\"" + reason + "\"]";
        String evidenceStatus = "PASSED".equals(caseStatus) ? "PASSED" : "ASSERTION_FAILED";
        return (ObjectNode) JSON.readTree("""
                {"schemaVersion":"bloge.testSuiteExecutionResponse.v1","suiteRunId":"suite-run-1",
                 "evidenceFingerprint":"%1$s","evidence":{"schemaVersion":"bloge.testSuiteRunEvidence.v1",
                   "suiteRunId":"suite-run-1","status":"%2$s","clientRequestId":"pipeline-1",
                   "executionPurpose":"TEST_SUITE_EXECUTION",
                   "suiteRef":{"suiteId":"suite-1","revision":1,"fingerprint":"%1$s"},
                   "target":{"kind":"GRAPH","id":"loanDecision","fingerprint":"%1$s"},
                   "startedAt":"2026-07-15T10:15:30Z","completedAt":"2026-07-15T10:15:31Z",
                   "caseResults":[{"caseId":"golden","caseType":"GOLDEN","status":"%3$s",
                     "runId":"run-1","fixtureBundleRef":{"fixtureBundleId":"f1","revision":1,
                     "fingerprint":"%1$s"},"evidenceStatus":"%4$s","evidenceClass":"CERTIFIABLE",
                     "assertionsEvaluated":1,"assertionsPassed":%5$d,"diagnosticCode":"",
                     "diagnostic":"private diagnostic"}],
                   "coverage":{"status":"%6$s","minimumCases":1,"completedCases":1,
                     "requiredCaseTypes":["GOLDEN"],"observedCaseTypes":["GOLDEN"],
                     "missingCaseTypes":[],"requiredInvocationSiteIds":[],"observedInvocationSiteIds":[],
                     "missingInvocationSiteIds":[],"requiredEdgeTransfers":[],"observedEdgeTransfers":[],
                     "missingEdgeTransfers":[],"minimumAssertionsPerCase":1,
                     "assertionDensityViolations":[],"fixtureConsumptionViolations":[],
                     "allCasesCompleted":true},
                   "promotion":{"status":"%7$s","reasons":%8$s,"allCasesPassed":%9$b,
                     "certifiableCases":1,"minimumCertifiableCases":1,
                     "targetCertificationEligible":true,"coverageSatisfied":%10$b,
                     "allCasesCompleted":true},"diagnostics":[],"metadata":{}}}
                """.formatted(FINGERPRINT, status, caseStatus, evidenceStatus,
                "PASSED".equals(caseStatus) ? 1 : 0, coverage, promotion, reasons,
                "PASSED".equals(caseStatus), "SATISFIED".equals(coverage)));
    }
}
