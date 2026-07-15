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
