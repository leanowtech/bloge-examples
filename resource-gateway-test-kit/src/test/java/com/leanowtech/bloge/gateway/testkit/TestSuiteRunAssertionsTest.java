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
