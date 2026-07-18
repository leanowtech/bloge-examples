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

    @Test
    void projectsAdmissionEvidenceWithoutClaimingBusinessExecutionOrPromotion() throws Exception {
        TestSuiteRun run = TestSuiteRun.from(JSON.readTree(schemaAdmissionSuiteResponse()));

        assertThat(run.evaluationMode()).isEqualTo(TestSuiteRun.EvaluationMode.SCHEMA_ADMISSION);
        assertThat(run.admissionPassed()).isTrue();
        assertThat(run.evaluationPassed()).isTrue();
        assertThat(run.passed()).isFalse();
        assertThat(run.requireAdmissionCoverage().status())
                .isEqualTo(TestSuiteRun.AdmissionCoverageStatus.SATISFIED);
        assertThat(run.admissionResults())
                .extracting(TestSuiteRun.AdmissionCaseResult::status)
                .containsExactly(TestSuiteRun.AdmissionCaseStatus.MATCHED);
        assertThat(run.caseResults().getFirst().runId()).isBlank();
        assertThat(run.promotionEligible()).isFalse();
        assertThat(run.gateFailureCodes(false)).isEmpty();
        assertThat(run.gateFailureCodes(true))
                .contains("PROMOTION_BLOCKED", "SCHEMA_ADMISSION_ONLY",
                        "BUSINESS_EXECUTION_NOT_PERFORMED");
        assertThatCode(() -> TestSuiteRunAssertions.assertAdmissionPassed(run))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> TestSuiteRunAssertions.assertPassed(run))
                .hasMessageContaining("business suite");
    }

    @Test
    void admissionProjectionRejectsMixedStructuralClaims() throws Exception {
        ObjectNode response = (ObjectNode) JSON.readTree(schemaAdmissionSuiteResponse());
        ((ObjectNode) response.at("/evidence/coverage")).put("status", "SATISFIED");

        assertThatThrownBy(() -> TestSuiteRun.from(response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authoritative schema");
    }

    @Test
    void projectsBoundedPropertyCounterexampleWithoutPayloadOrGlobalMinimalityClaim()
            throws Exception {
        TestSuiteRun run = TestSuiteRun.from(JSON.readTree(propertySuiteResponse()));

        assertThat(run.evaluationMode()).isEqualTo(TestSuiteRun.EvaluationMode.PROPERTY_EXECUTION);
        assertThat(run.propertyPassed()).isFalse();
        assertThat(run.evaluationPassed()).isFalse();
        assertThat(run.passed()).isFalse();
        assertThat(run.requirePropertyCoverage().status())
                .isEqualTo(TestSuiteRun.PropertyCoverageStatus.COUNTEREXAMPLE);
        assertThat(run.propertyTrialResults())
                .extracting(TestSuiteRun.PropertyTrialResult::status)
                .containsExactly(TestSuiteRun.PropertyTrialStatus.COUNTEREXAMPLE);
        TestSuiteRun.CounterexampleRef counterexample =
                TestSuiteRunAssertions.assertCounterexampleFound(run);
        assertThat(counterexample.caseId()).isEqualTo("property-001-shrink-001");
        assertThat(counterexample.minimalityScope()).isEqualTo("PRECOMPUTED_SHRINK_PATH");
        assertThat(counterexample.globallyMinimal()).isFalse();
        assertThat(run.gateFailureCodes(false)).contains(
                "SUITE_STATUS_COMPLETED_WITH_FAILURES",
                "PROPERTY_COUNTEREXAMPLE", "PROPERTY_COUNTEREXAMPLES_PRESENT");
        assertThatThrownBy(() -> TestSuiteRunAssertions.assertPropertySatisfied(run))
                .hasMessageContaining("COUNTEREXAMPLE")
                .hasMessageNotContaining("generated-root")
                .hasMessageNotContaining("generated-shrink");
        assertThat(run.rawResponse().toString())
                .doesNotContain("generated-root", "generated-shrink");
    }

    @Test
    void acceptsSatisfiedBoundedPropertyClosureAsItsOwnEvaluationMode() throws Exception {
        ObjectNode response = (ObjectNode) JSON.readTree(propertySuiteResponse());
        ObjectNode evidence = (ObjectNode) response.path("evidence");
        evidence.put("status", "PASSED");
        for (int index = 0; index < 2; index++) {
            ObjectNode common = (ObjectNode) evidence.path("caseResults").get(index);
            common.put("status", "PASSED");
            common.put("evidenceStatus", "PASSED");
            common.put("assertionsPassed", 1);
            common.put("diagnosticCode", "");
        }
        ObjectNode trial = (ObjectNode) evidence.path("propertyTrialResults").get(0);
        trial.put("status", "SATISFIED");
        trial.putNull("minimalObservedCounterexample");
        ObjectNode root = (ObjectNode) trial.path("rootResult");
        root.put("status", "SATISFIED");
        root.put("evidenceStatus", "PASSED");
        root.put("assertionsPassed", 1);
        root.put("diagnosticCode", "");
        ObjectNode shrink = (ObjectNode) trial.path("shrinkResults").get(0);
        shrink.put("status", "SATISFIED");
        shrink.put("evidenceStatus", "PASSED");
        shrink.put("assertionsPassed", 1);
        shrink.put("diagnosticCode", "");
        ObjectNode coverage = (ObjectNode) evidence.path("propertyCoverage");
        coverage.put("status", "SATISFIED");
        coverage.put("satisfiedCases", 2);
        coverage.put("counterexampleCases", 0);
        coverage.putArray("minimalObservedCounterexamples");

        TestSuiteRun run = TestSuiteRun.from(response);

        assertThat(run.propertyPassed()).isTrue();
        assertThat(run.evaluationPassed()).isTrue();
        assertThatCode(() -> TestSuiteRunAssertions.assertPropertySatisfied(run))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> TestSuiteRunAssertions.assertCounterexampleFound(run))
                .hasMessageContaining("no observed counterexample");
    }

    @Test
    void rejectsProducerOwnedPropertyMinimumAndCoverageCounterDrift() throws Exception {
        ObjectNode wrongMinimum = (ObjectNode) JSON.readTree(propertySuiteResponse());
        ((ObjectNode) wrongMinimum.at(
                "/evidence/propertyTrialResults/0/minimalObservedCounterexample"))
                .put("caseId", "property-001")
                .put("inputFingerprint", FINGERPRINT)
                .put("complexity", 2);

        assertThatThrownBy(() -> TestSuiteRun.from(wrongMinimum))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be derived");

        ObjectNode wrongCoverage = (ObjectNode) JSON.readTree(propertySuiteResponse());
        ((ObjectNode) wrongCoverage.at("/evidence/propertyCoverage"))
                .put("counterexampleCases", 1);

        assertThatThrownBy(() -> TestSuiteRun.from(wrongCoverage))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compatibility closure");
    }

    @Test
    void projectsPureDslMutationBaselineMutantsAndIndependentlyDerivedScore()
            throws Exception {
        TestSuiteRun run = TestSuiteRun.from(JSON.readTree(mutationSuiteResponse()));

        assertThat(run.evaluationMode())
                .isEqualTo(TestSuiteRun.EvaluationMode.PURE_DSL_MUTATION);
        assertThat(run.passed()).isFalse();
        assertThat(run.mutationPassed()).isTrue();
        assertThat(run.evaluationPassed()).isTrue();
        assertThat(run.mutationBaselineStatus())
                .contains(TestSuiteRun.MutationBaselineStatus.PASSED);
        assertThat(run.mutationPlan().orElseThrow().policy().maxMutants()).isEqualTo(2);
        assertThat(run.mutantResults())
                .extracting(TestSuiteRun.MutantResult::status)
                .containsExactly(TestSuiteRun.MutantStatus.KILLED,
                        TestSuiteRun.MutantStatus.SURVIVED);
        assertThat(run.requireMutationScore()).satisfies(score -> {
            assertThat(score.status()).isEqualTo(TestSuiteRun.MutationScoreStatus.SATISFIED);
            assertThat(score.killedMutants()).isEqualTo(1);
            assertThat(score.survivedMutants()).isEqualTo(1);
            assertThat(score.denominatorMutants()).isEqualTo(2);
            assertThat(score.scoreBasisPoints()).isEqualTo(5_000);
            assertThat(score.equivalentMutantsExcluded()).isZero();
        });
        assertThat(run.gateFailureCodes(false)).isEmpty();
        assertThatCode(() -> TestSuiteRunAssertions.assertMutationSatisfied(run))
                .doesNotThrowAnyException();
        assertThatThrownBy(run::requirePropertyCoverage)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PROPERTY_COVERAGE_UNAVAILABLE");
    }

    @Test
    void rejectsProducerOwnedMutationScoreCounterDrift() throws Exception {
        ObjectNode response = (ObjectNode) JSON.readTree(mutationSuiteResponse());
        ObjectNode score = (ObjectNode) response.at("/evidence/mutationScore");
        score.put("killedMutants", 2);
        score.put("survivedMutants", 0);
        score.put("scoreBasisPoints", 10_000);

        assertThatThrownBy(() -> TestSuiteRun.from(response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("independently derived");
    }

    @Test
    void exposesMutationPolicyFailureCodesWithoutPayloads() throws Exception {
        ObjectNode response = (ObjectNode) JSON.readTree(mutationSuiteResponse());
        ObjectNode evidence = (ObjectNode) response.path("evidence");
        evidence.put("status", "COMPLETED_WITH_FAILURES");
        ObjectNode score = (ObjectNode) evidence.path("mutationScore");
        ((ObjectNode) score.path("policy")).put("minimumScoreBasisPoints", 6_000);
        score.put("status", "UNSATISFIED");
        score.putArray("reasons").add("MUTATION_SCORE_BELOW_THRESHOLD");
        ObjectNode promotion = (ObjectNode) evidence.path("promotion");
        promotion.put("status", "BLOCKED");
        promotion.putArray("reasons").add("MUTATION_SCORE_UNSATISFIED");

        TestSuiteRun run = TestSuiteRun.from(response);

        assertThat(run.mutationPassed()).isFalse();
        assertThat(run.gateFailureCodes(false)).containsExactly(
                "SUITE_STATUS_COMPLETED_WITH_FAILURES",
                "MUTATION_SCORE_UNSATISFIED",
                "MUTATION_SCORE_BELOW_THRESHOLD");
        assertThatThrownBy(() -> TestSuiteRunAssertions.assertMutationSatisfied(run))
                .hasMessageContaining("UNSATISFIED:5000")
                .hasMessageNotContaining("applicant")
                .hasMessageNotContaining("request")
                .hasMessageNotContaining("response");
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

    static String schemaAdmissionSuiteResponse() {
        return """
                {"schemaVersion":"bloge.testSuiteExecutionResponse.v4","suiteRunId":"suite-run-admission",
                 "evidenceFingerprint":"%1$s","evidence":{"schemaVersion":"bloge.testSuiteRunEvidence.v3",
                   "suiteRunId":"suite-run-admission","clientRequestId":"admission-ci-1","status":"PASSED",
                   "executionPurpose":"SCHEMA_ADMISSION_SUITE_EXECUTION",
                   "suiteRef":{"suiteId":"suite-boundary","revision":3,"fingerprint":"%1$s"},
                   "target":{"kind":"OPERATOR","id":"customer.normalize/v2","fingerprint":"%1$s"},
                   "startedAt":"2026-07-17T01:00:00Z","completedAt":"2026-07-17T01:00:01Z",
                   "caseResults":[{"caseId":"baseline","caseType":"BOUNDARY",
                     "fixtureBundleRef":{"fixtureBundleId":"boundary-baseline","revision":1,
                       "fingerprint":"%1$s"},"status":"PASSED","runId":"","evidenceStatus":null,
                     "evidenceClass":null,"assertionsEvaluated":0,"assertionsPassed":0,
                     "diagnosticCode":"","diagnostic":""}],
                   "coverage":{"status":"NOT_EVALUATED","minimumCases":0,"completedCases":0,
                     "requiredCaseTypes":[],"observedCaseTypes":[],"missingCaseTypes":[],
                     "requiredInvocationSiteIds":[],"observedInvocationSiteIds":[],
                     "missingInvocationSiteIds":[],"requiredEdgeTransfers":[],"observedEdgeTransfers":[],
                     "missingEdgeTransfers":[],"minimumAssertionsPerCase":0,
                     "assertionDensityViolations":[],"fixtureConsumptionViolations":[],
                     "allCasesCompleted":false},
                   "promotion":{"status":"BLOCKED","reasons":["BUSINESS_EXECUTION_NOT_PERFORMED",
                     "SCHEMA_ADMISSION_ONLY"],"allCasesPassed":true,"certifiableCases":0,
                     "minimumCertifiableCases":0,"targetCertificationEligible":false,
                     "coverageSatisfied":false,"allCasesCompleted":true},
                   "evaluationMode":"SCHEMA_ADMISSION","boundaryPlanFingerprint":"%1$s",
                   "inputSchemaFingerprint":"%1$s","generatorVersion":"boundary-generator.v1",
                   "verificationMode":"EXACT_SHARED_VALIDATOR","sourcePlanStatus":"GENERATED",
                   "sourceCoverageGapCount":0,"coverageGapsAccepted":false,
                   "admissionResults":[{"caseId":"baseline","status":"MATCHED",
                     "expectedOutcome":"ACCEPTED","observedOutcome":"ACCEPTED",
                     "expectedValidationCodes":[],"observedValidationCodes":[],"diagnosticCode":""}],
                   "admissionCoverage":{"status":"SATISFIED","requiredCases":1,"evaluatedCases":1,
                     "matchedCases":1,"expectationMismatchCaseIds":[],"provenanceMismatchCaseIds":[],
                     "incompleteCaseIds":[],"allCasesCompleted":true},"diagnostics":[],
                   "metadata":{"businessTargetInvoked":false,"childRunCount":0}},
                 "attestation":{"schemaVersion":"bloge.testSuiteRunAttestation.v3",
                   "signatureStatus":"VERIFIED","scope":"TERMINAL","suiteRunId":"suite-run-admission",
                   "suiteRef":{"suiteId":"suite-boundary","revision":3,"fingerprint":"%1$s"},
                   "requestFingerprint":"%1$s","aggregateEvidenceFingerprint":"%1$s",
                   "childEvidenceRefs":[],"signedAt":"2026-07-17T01:00:01Z","keyId":"test-key-1",
                   "algorithm":"Ed25519","signature":"AA==","independentlyVerifiable":true}}
                """.formatted(FINGERPRINT);
    }

    static String propertySuiteResponse() {
        return """
                {"schemaVersion":"bloge.testSuiteExecutionResponse.v5","suiteRunId":"suite-run-property",
                 "evidenceFingerprint":"%1$s","evidence":{"schemaVersion":"bloge.testSuiteRunEvidence.v4",
                   "suiteRunId":"suite-run-property","clientRequestId":"property-ci-1",
                   "status":"COMPLETED_WITH_FAILURES","executionPurpose":"PROPERTY_SUITE_EXECUTION",
                   "suiteRef":{"suiteId":"suite-property","revision":4,"fingerprint":"%1$s"},
                   "target":{"kind":"GRAPH","id":"loanDecision","fingerprint":"%1$s"},
                   "startedAt":"2026-07-17T02:00:00Z","completedAt":"2026-07-17T02:00:02Z",
                   "caseResults":[
                     {"caseId":"property-001","caseType":"PROPERTY",
                      "fixtureBundleRef":{"fixtureBundleId":"property-fixture","revision":1,
                        "fingerprint":"%1$s"},"status":"FAILED","runId":"property-run-root",
                      "evidenceStatus":"ASSERTION_FAILED","evidenceClass":"CERTIFIABLE",
                      "assertionsEvaluated":1,"assertionsPassed":0,
                      "diagnosticCode":"ASSERTION_FAILED","diagnostic":""},
                     {"caseId":"property-001-shrink-001","caseType":"PROPERTY",
                      "fixtureBundleRef":{"fixtureBundleId":"property-fixture","revision":1,
                        "fingerprint":"%1$s"},"status":"FAILED","runId":"property-run-shrink",
                      "evidenceStatus":"ASSERTION_FAILED","evidenceClass":"CERTIFIABLE",
                      "assertionsEvaluated":1,"assertionsPassed":0,
                      "diagnosticCode":"ASSERTION_FAILED","diagnostic":""}],
                   "coverage":{"status":"SATISFIED","minimumCases":2,"completedCases":2,
                     "requiredCaseTypes":["PROPERTY"],"observedCaseTypes":["PROPERTY"],
                     "missingCaseTypes":[],"requiredInvocationSiteIds":[],
                     "observedInvocationSiteIds":[],"missingInvocationSiteIds":[],
                     "requiredEdgeTransfers":[],"observedEdgeTransfers":[],
                     "missingEdgeTransfers":[],"minimumAssertionsPerCase":1,
                     "assertionDensityViolations":[],"fixtureConsumptionViolations":[],
                     "allCasesCompleted":true},
                   "promotion":{"status":"BLOCKED","reasons":["CASE_FAILURES_PRESENT"],
                     "allCasesPassed":false,"certifiableCases":2,"minimumCertifiableCases":2,
                     "targetCertificationEligible":true,"coverageSatisfied":true,
                     "allCasesCompleted":true},
                   "evaluationMode":"PROPERTY_EXECUTION","quantification":"BOUNDED_SAMPLED",
                   "exhaustive":false,"propertyPlanFingerprint":"%1$s",
                   "inputSchemaFingerprint":"%1$s",
                   "generationPolicy":{"generatorVersion":"property-cases-v1","seed":42,
                     "requestedTrials":1,"maxShrinkSteps":1,"maxCases":2,
                     "maxGenerationAttempts":32,"maxDepth":8,"maxCollectionItems":32,
                     "verificationMode":"VISUAL_SCHEMA_VALIDATOR_PROOF"},
                   "sourcePlanStatus":"GENERATED","generationGapsAccepted":false,
                   "generationGaps":[],"propertyTrialResults":[{
                     "trialId":"property-001","status":"COUNTEREXAMPLE",
                     "rootResult":{"caseId":"property-001","role":"ROOT","parentCaseId":"",
                       "shrinkStep":0,"inputFingerprint":"%1$s","complexity":2,
                       "status":"COUNTEREXAMPLE","runId":"property-run-root",
                       "evidenceStatus":"ASSERTION_FAILED","assertionsEvaluated":1,
                       "assertionsPassed":0,"diagnosticCode":"ASSERTION_FAILED"},
                     "shrinkResults":[{"caseId":"property-001-shrink-001","role":"SHRINK",
                       "parentCaseId":"property-001","shrinkStep":1,
                       "inputFingerprint":"sha256:%2$s","complexity":1,
                       "status":"COUNTEREXAMPLE","runId":"property-run-shrink",
                       "evidenceStatus":"ASSERTION_FAILED","assertionsEvaluated":1,
                       "assertionsPassed":0,"diagnosticCode":"ASSERTION_FAILED"}],
                     "minimalObservedCounterexample":{"caseId":"property-001-shrink-001",
                       "inputFingerprint":"sha256:%2$s","complexity":1,
                       "minimalityScope":"PRECOMPUTED_SHRINK_PATH","globallyMinimal":false}}],
                   "propertyCoverage":{"status":"COUNTEREXAMPLE","requiredTrials":1,
                     "completedTrials":1,"requiredCases":2,"evaluatedCases":2,
                     "satisfiedCases":0,"counterexampleCases":2,"executionFailedCaseIds":[],
                     "incompleteCaseIds":[],"minimalObservedCounterexamples":[{
                       "caseId":"property-001-shrink-001","inputFingerprint":"sha256:%2$s",
                       "complexity":1,"minimalityScope":"PRECOMPUTED_SHRINK_PATH",
                       "globallyMinimal":false}],"allCasesCompleted":true,
                     "minimalityScope":"PRECOMPUTED_SHRINK_PATH","globallyMinimal":false},
                   "diagnostics":[],"metadata":{}},
                 "attestation":{"schemaVersion":"bloge.testSuiteRunAttestation.v4",
                   "signatureStatus":"VERIFIED","scope":"TERMINAL","suiteRunId":"suite-run-property",
                   "suiteRef":{"suiteId":"suite-property","revision":4,"fingerprint":"%1$s"},
                   "requestFingerprint":"%1$s","aggregateEvidenceFingerprint":"%1$s",
                   "childEvidenceRefs":[
                     {"caseId":"property-001","runId":"property-run-root",
                      "evidenceFingerprint":"%1$s"},
                     {"caseId":"property-001-shrink-001","runId":"property-run-shrink",
                      "evidenceFingerprint":"%1$s"}],
                   "signedAt":"2026-07-17T02:00:02Z","keyId":"test-key-1",
                   "algorithm":"Ed25519","signature":"AA==","independentlyVerifiable":true}}
                """.formatted(FINGERPRINT, "b".repeat(64));
    }

    static String mutationSuiteResponse() {
        return """
                {"schemaVersion":"bloge.testSuiteExecutionResponse.v6","suiteRunId":"suite-run-mutation",
                 "evidenceFingerprint":"%1$s","evidence":{"schemaVersion":"bloge.testSuiteRunEvidence.v5",
                   "suiteRunId":"suite-run-mutation","clientRequestId":"mutation-ci-1","status":"PASSED",
                   "executionPurpose":"MUTATION_SUITE_EXECUTION",
                   "suiteRef":{"suiteId":"suite-mutation","revision":5,"fingerprint":"%1$s"},
                   "target":{"kind":"GRAPH","id":"loanDecision","fingerprint":"%1$s"},
                   "startedAt":"2026-07-17T03:00:00Z","completedAt":"2026-07-17T03:00:03Z",
                   "caseResults":[{"caseId":"golden","caseType":"GOLDEN",
                     "fixtureBundleRef":{"fixtureBundleId":"loan-fixture","revision":1,
                       "fingerprint":"%1$s"},"status":"PASSED","runId":"baseline-run-golden",
                     "evidenceStatus":"PASSED","evidenceClass":"CERTIFIABLE",
                     "assertionsEvaluated":2,"assertionsPassed":2,"diagnosticCode":"",
                     "diagnostic":""}],
                   "coverage":{"status":"SATISFIED","minimumCases":1,"completedCases":1,
                     "requiredCaseTypes":["GOLDEN"],"observedCaseTypes":["GOLDEN"],
                     "missingCaseTypes":[],"requiredInvocationSiteIds":[],
                     "observedInvocationSiteIds":[],"missingInvocationSiteIds":[],
                     "requiredEdgeTransfers":[],"observedEdgeTransfers":[],
                     "missingEdgeTransfers":[],"minimumAssertionsPerCase":1,
                     "assertionDensityViolations":[],"fixtureConsumptionViolations":[],
                     "allCasesCompleted":true},
                   "promotion":{"status":"ELIGIBLE","reasons":[],"allCasesPassed":true,
                     "certifiableCases":1,"minimumCertifiableCases":1,
                     "targetCertificationEligible":true,"coverageSatisfied":true,
                     "allCasesCompleted":true},
                   "evaluationMode":"PURE_DSL_MUTATION","sourceFormat":"bloge-dsl.ast.v1",
                   "baselineSourceFingerprint":"%1$s",
                   "baselineGraphArtifactFingerprint":"sha256:%2$s",
                   "mutationPlanFingerprint":"sha256:%3$s",
                   "mutationPolicy":{"plannerVersion":"pure-dsl-mutations-v1","maxMutants":2,
                     "sourceFormat":"bloge-dsl.ast.v1",
                     "verificationMode":"BLOGE_DSL_AST_RECOMPILE_PROOF",
                     "externalOperatorMutation":false,"equivalentMutantDetection":false},
                   "sourcePlanStatus":"GENERATED","planningGapsAccepted":false,
                   "planningGaps":[],
                   "oracleSuiteRef":{"suiteId":"suite-oracle","revision":2,
                     "fingerprint":"%1$s","schemaVersion":"bloge.testSuite.v1"},
                   "baselineStatus":"PASSED","mutantResults":[
                     {"mutant":{"mutantId":"mutant-001","kind":"FALLBACK_REMOVED",
                       "astPath":"/members/0/fallback","sourceLine":2,"sourceColumn":3,
                       "mutantSourceFingerprint":"sha256:%4$s",
                       "mutantGraphArtifactFingerprint":"sha256:%5$s",
                       "mutantTargetFingerprint":"sha256:%6$s",
                       "equivalenceClassification":"UNKNOWN"},"status":"KILLED",
                      "caseResults":[{"caseId":"golden",
                        "fixtureBundleRef":{"fixtureBundleId":"loan-fixture","revision":1,
                          "fingerprint":"%1$s"},"mutantTargetFingerprint":"sha256:%6$s",
                        "status":"ASSERTION_KILLED","runId":"mutant-run-killed",
                        "evidenceFingerprint":"%1$s","evidenceStatus":"ASSERTION_FAILED",
                        "evidenceClass":"CERTIFIABLE","assertionsEvaluated":2,
                        "assertionsPassed":1,"diagnosticCode":"ASSERTION_FAILED"}],
                      "killingCaseIds":["golden"]},
                     {"mutant":{"mutantId":"mutant-002","kind":"DECISION_CONDITION_NEGATED",
                       "astPath":"/members/1/rules/0/condition","sourceLine":8,"sourceColumn":5,
                       "mutantSourceFingerprint":"sha256:%7$s",
                       "mutantGraphArtifactFingerprint":"sha256:%8$s",
                       "mutantTargetFingerprint":"sha256:%9$s",
                       "equivalenceClassification":"UNKNOWN"},"status":"SURVIVED",
                      "caseResults":[{"caseId":"golden",
                        "fixtureBundleRef":{"fixtureBundleId":"loan-fixture","revision":1,
                          "fingerprint":"%1$s"},"mutantTargetFingerprint":"sha256:%9$s",
                        "status":"SURVIVED","runId":"mutant-run-survived",
                        "evidenceFingerprint":"%1$s","evidenceStatus":"PASSED",
                        "evidenceClass":"CERTIFIABLE","assertionsEvaluated":2,
                        "assertionsPassed":2,"diagnosticCode":""}],"killingCaseIds":[]}],
                   "mutationScore":{"status":"SATISFIED","policy":{
                     "minimumScoreBasisPoints":5000,"maximumInconclusiveMutants":0,
                     "requireNoSurvivors":false,"excludeEquivalentMutants":false},
                     "plannedMutants":2,"killedMutants":1,"survivedMutants":1,
                     "inconclusiveMutants":0,"unclassifiedMutants":0,
                     "denominatorMutants":2,"scoreBasisPoints":5000,
                     "equivalentMutantsExcluded":0,"reasons":[]},
                   "diagnostics":[],"metadata":{}},
                 "attestation":{"schemaVersion":"bloge.testSuiteRunAttestation.v5",
                   "signatureStatus":"VERIFIED","scope":"TERMINAL",
                   "suiteRunId":"suite-run-mutation",
                   "suiteRef":{"suiteId":"suite-mutation","revision":5,"fingerprint":"%1$s"},
                   "requestFingerprint":"%1$s","aggregateEvidenceFingerprint":"%1$s",
                   "childEvidenceRefs":[
                     {"caseId":"baseline/golden","runId":"baseline-run-golden",
                      "evidenceFingerprint":"%1$s"},
                     {"caseId":"mutant-001/golden","runId":"mutant-run-killed",
                      "evidenceFingerprint":"%1$s"},
                     {"caseId":"mutant-002/golden","runId":"mutant-run-survived",
                      "evidenceFingerprint":"%1$s"}],
                   "signedAt":"2026-07-17T03:00:03Z","keyId":"test-key-1",
                   "algorithm":"Ed25519","signature":"AA==","independentlyVerifiable":true}}
                """.formatted(FINGERPRINT, "b".repeat(64), "c".repeat(64),
                "d".repeat(64), "e".repeat(64), "f".repeat(64),
                "1".repeat(64), "2".repeat(64), "3".repeat(64));
    }
}
