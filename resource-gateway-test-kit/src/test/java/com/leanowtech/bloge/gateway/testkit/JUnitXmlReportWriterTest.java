package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
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
                 "evidenceFingerprint":"%1$s","evidence":{"status":"COMPLETED_WITH_FAILURES",
                   "clientRequestId":"pipeline-7",
                   "suiteRef":{"suiteId":"loan-policy","revision":3,"fingerprint":"%1$s"},
                   "target":{"kind":"GRAPH","id":"loanDecision","fingerprint":"%1$s"},
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
                     "missingCaseTypes":[],"missingInvocationSiteIds":[],"missingEdgeTransfers":[],
                     "assertionDensityViolations":["negative<case>"],"fixtureConsumptionViolations":[]},
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

    private static String run(String runId, String status, String evidenceClass, String diagnostic) {
        return """
                {"schemaVersion":"bloge.testExecutionResponse.v1","runId":"%s",
                 "evidence":{"status":"%s","evidenceClass":"%s","targetFingerprint":"sha256:target",
                   "fixtureBundleFingerprint":"sha256:fixture","planFingerprint":"sha256:plan",
                   "nodeTrace":[{"nodeId":"node","input":"private-payload","output":"private-payload"}],
                   "fixtureConsumptions":[],"assertionResults":[],"diagnostics":["%s"]}}
                """.formatted(runId, status, evidenceClass, diagnostic);
    }
}
