package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JUnitXmlReportWriterTest {

    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesCiCompatibleEscapedReportWithoutPayloadEvidence() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TestRun passed = TestRun.from(mapper.readTree(run("run-1", "PASSED", "CERTIFIABLE", "")));
        TestRun failed = TestRun.from(mapper.readTree(run("run-<2>", "EXECUTION_FAILED", "EXPLORATORY",
                "bounded & useful")));
        Path report = temporaryDirectory.resolve("reports/resource-gateway.xml");

        TestRunBatch summary = new TestRunBatch(List.of(passed, failed));
        JUnitXmlReportWriter.Report result = JUnitXmlReportWriter.write(report,
                "resource-gateway contract <suite>", summary.runs());

        assertThat(result.tests()).isEqualTo(2);
        assertThat(result.failures()).isEqualTo(1);
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(summary.exitCode()).isEqualTo(1);
        assertThat(Files.readString(report))
                .contains("run-&lt;2&gt;")
                .doesNotContain("bounded &amp; useful")
                .doesNotContain("private-payload");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(report.toFile());
        assertThat(document.getDocumentElement().getAttribute("tests")).isEqualTo("2");
        assertThat(document.getDocumentElement().getAttribute("failures")).isEqualTo("1");
        assertThat(document.getElementsByTagName("testcase").getLength()).isEqualTo(2);
        assertThat(document.getElementsByTagName("failure").getLength()).isEqualTo(1);
    }

    @Test
    void writesSuiteCasesAndAFailClosedAggregateGateWithoutDiagnostics() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TestSuiteRun run = TestSuiteRun.from(mapper.readTree("""
                {"schemaVersion":"bloge.testSuiteExecutionResponse.v1","suiteRunId":"suite-run-7",
                 "evidenceFingerprint":"%1$s","evidence":{"schemaVersion":"bloge.testSuiteRunEvidence.v1",
                   "suiteRunId":"suite-run-7","status":"COMPLETED_WITH_FAILURES",
                   "clientRequestId":"pipeline-7","executionPurpose":"TEST_SUITE_EXECUTION",
                   "suiteRef":{"suiteId":"loan-policy","revision":3,"fingerprint":"%1$s"},
                   "target":{"kind":"GRAPH","id":"loanDecision","fingerprint":"%1$s"},
                   "startedAt":"2026-07-15T10:15:30Z","completedAt":"2026-07-15T10:15:31Z",
                   "caseResults":[
                     {"caseId":"golden","caseType":"GOLDEN","status":"PASSED","runId":"run-1",
                      "fixtureBundleRef":{"fixtureBundleId":"f1","revision":1,"fingerprint":"%1$s"},
                      "evidenceStatus":"PASSED","evidenceClass":"CERTIFIABLE",
                      "assertionsEvaluated":2,"assertionsPassed":2,"diagnosticCode":"","diagnostic":""},
                     {"caseId":"negative<case>","caseType":"NEGATIVE","status":"FAILED","runId":"run-2",
                      "fixtureBundleRef":{"fixtureBundleId":"f2","revision":1,"fingerprint":"%1$s"},
                      "evidenceStatus":"ASSERTION_FAILED","evidenceClass":"CERTIFIABLE",
                      "assertionsEvaluated":1,"assertionsPassed":0,"diagnosticCode":"ASSERTION_FAILED",
                      "diagnostic":"private customer payload"}],
                   "coverage":{"status":"UNSATISFIED","completedCases":2,"minimumCases":2,
                     "requiredCaseTypes":["GOLDEN","NEGATIVE"],
                     "observedCaseTypes":["GOLDEN","NEGATIVE"],"missingCaseTypes":[],
                     "requiredInvocationSiteIds":[],"observedInvocationSiteIds":[],
                     "missingInvocationSiteIds":[],"requiredEdgeTransfers":[],
                     "observedEdgeTransfers":[],"missingEdgeTransfers":[],
                     "minimumAssertionsPerCase":2,
                     "assertionDensityViolations":["negative<case>"],"fixtureConsumptionViolations":[],
                     "allCasesCompleted":true},
                   "promotion":{"status":"BLOCKED","reasons":["COVERAGE_UNSATISFIED"],
                     "allCasesPassed":false,"certifiableCases":2,"minimumCertifiableCases":2,
                     "targetCertificationEligible":true,"coverageSatisfied":false,"allCasesCompleted":true},
                   "diagnostics":["private aggregate payload"],"metadata":{"private":"payload"}}}
                """.formatted(FINGERPRINT)));
        Path report = temporaryDirectory.resolve("reports/governed-suite.xml");

        JUnitXmlReportWriter.Report result = JUnitXmlReportWriter.writeSuite(report, run, true);

        assertThat(result.tests()).isEqualTo(3);
        assertThat(result.failures()).isEqualTo(2);
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(report))
                .contains("negative&lt;case&gt;")
                .contains("COVERAGE_UNSATISFIED")
                .doesNotContain("private customer payload")
                .doesNotContain("private aggregate payload")
                .doesNotContain("payload\"");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(report.toFile());
        assertThat(document.getDocumentElement().getAttribute("tests")).isEqualTo("3");
        assertThat(document.getDocumentElement().getAttribute("failures")).isEqualTo("2");
        assertThat(document.getElementsByTagName("testcase").getLength()).isEqualTo(3);
        assertThat(document.getElementsByTagName("failure").getLength()).isEqualTo(2);
    }

    @Test
    void writesPassingAdmissionReportWithoutInventingBusinessChildRuns() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TestSuiteRun run = TestSuiteRun.from(
                mapper.readTree(TestSuiteRunAssertionsTest.schemaAdmissionSuiteResponse()));
        Path report = temporaryDirectory.resolve("reports/schema-admission.xml");

        JUnitXmlReportWriter.Report result = JUnitXmlReportWriter.writeSuite(report, run, false);

        assertThat(result.tests()).isEqualTo(2);
        assertThat(result.failures()).isZero();
        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(report))
                .contains("evaluationMode=SCHEMA_ADMISSION")
                .contains("admissionCoverage=SATISFIED")
                .doesNotContain("child runId=")
                .doesNotContain("BUSINESS_EXECUTION_NOT_PERFORMED");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(report.toFile());
        assertThat(document.getDocumentElement().getAttribute("tests")).isEqualTo("2");
        assertThat(document.getDocumentElement().getAttribute("failures")).isEqualTo("0");
        assertThat(document.getElementsByTagName("failure").getLength()).isZero();
    }

    @Test
    void writesMutationClassificationAndLetsTheImmutableScorePolicyOwnTheGate() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TestSuiteRun run = TestSuiteRun.from(
                mapper.readTree(TestSuiteRunAssertionsTest.mutationSuiteResponse()));
        Path report = temporaryDirectory.resolve("reports/mutation.xml");

        JUnitXmlReportWriter.Report result = JUnitXmlReportWriter.writeSuite(report, run, true);

        assertThat(result.tests()).isEqualTo(4);
        assertThat(result.failures()).isZero();
        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(report))
                .contains("name=\"mutant-001\"")
                .contains("name=\"mutant-002\"")
                .contains("status=KILLED")
                .contains("status=SURVIVED")
                .contains("mutationBaseline=PASSED")
                .contains("mutationScore=5000")
                .contains("mutationScoreStatus=SATISFIED")
                .doesNotContain("/members/")
                .doesNotContain("/fallback");
    }

    @Test
    void writesOneAggregateFailureWhenMutationPolicyRejectsAnOtherwiseValidScore()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode response = (ObjectNode) mapper.readTree(
                TestSuiteRunAssertionsTest.mutationSuiteResponse());
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
        Path report = temporaryDirectory.resolve("reports/mutation-failed.xml");

        JUnitXmlReportWriter.Report result = JUnitXmlReportWriter.writeSuite(report, run, false);

        assertThat(result.tests()).isEqualTo(4);
        assertThat(result.failures()).isEqualTo(1);
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(report))
                .contains("MUTATION_SCORE_UNSATISFIED")
                .contains("MUTATION_SCORE_BELOW_THRESHOLD");
    }

    @Test
    void writesVerifiedStableCasesTrustAndAggregateGateWithoutPayloads() throws Exception {
        TestSuiteStabilityTestFixtures.Fixture fixture = TestSuiteStabilityTestFixtures.fixture();
        TestSuiteStabilityRun run = fixture.run();
        TestSuiteStabilityEvidenceVerifier.VerificationResult verification =
                stabilityVerifier().verify(
                        run, fixture.keySet(), fixture.keySet().snapshotFingerprint());
        Path report = temporaryDirectory.resolve("reports/stability.xml");

        JUnitXmlReportWriter.Report result = JUnitXmlReportWriter.writeStability(
                report, run, verification);

        assertThat(result.tests()).isEqualTo(3);
        assertThat(result.failures()).isZero();
        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(report))
                .contains("name=\"case-golden\"")
                .contains("name=\"stability-attestation\"")
                .contains("name=\"stability-gate\"")
                .contains("verification=VERIFIED")
                .contains("trustVerified=true")
                .doesNotContain("nightly");
    }

    @Test
    void failsFlakyAndUntrustedStabilityEvidenceDeterministically() throws Exception {
        TestSuiteStabilityTestFixtures.Fixture fixture = TestSuiteStabilityTestFixtures.fixture();
        ObjectNode response = fixture.copyResponse();
        TestSuiteStabilityTestFixtures.makeFlaky(response, fixture.keyPair());
        TestSuiteStabilityRun run = TestSuiteStabilityRun.from(response);
        TestSuiteStabilityEvidenceVerifier.VerificationResult invalid =
                stabilityVerifier().verify(run, fixture.keySet(), "sha256:" + "8".repeat(64));
        Path report = temporaryDirectory.resolve("reports/flaky-untrusted.xml");

        JUnitXmlReportWriter.Report result = JUnitXmlReportWriter.writeStability(
                report, run, invalid);

        assertThat(result.tests()).isEqualTo(3);
        assertThat(result.failures()).isEqualTo(3);
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(report))
                .contains("ResourceGateway.FLAKY")
                .contains("STABILITY_ATTESTATION_UNVERIFIED")
                .contains("STABILITY_GATE_BLOCKED")
                .contains("KEY_SET_PIN_MISMATCH")
                .contains("STABILITY_FLAKY")
                .contains("FLAKY_CASE_OBSERVED")
                .doesNotContain("nightly");
    }

    private static TestSuiteStabilityEvidenceVerifier stabilityVerifier() {
        return new TestSuiteStabilityEvidenceVerifier(Clock.fixed(
                TestSuiteStabilityTestFixtures.SIGNED_AT, ZoneOffset.UTC));
    }

    private static String run(String runId, String status, String evidenceClass, String diagnostic) {
        return """
                {"schemaVersion":"bloge.testExecutionResponse.v1","runId":"%s",
                 "evidence":{"schemaVersion":"bloge.testRunEvidence.v1",
                   "status":"%s","evidenceClass":"%s","targetFingerprint":"sha256:target",
                   "fixtureBundleFingerprint":"sha256:fixture","planFingerprint":"sha256:plan",
                   "nodeTrace":[{"nodeId":"node","input":"private-payload","output":"private-payload"}],
                   "fixtureConsumptions":[],"assertionResults":[],"diagnostics":["%s"]}}
                """.formatted(runId, status, evidenceClass, diagnostic);
    }
}
