package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRunAssertionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void acceptsPassedCertifiableRunWithSatisfiedFixturesAndNoRealCalls() throws Exception {
        TestRun run = TestRun.from(JSON.readTree(run("PASSED", "CERTIFIABLE", "MOCKED", true)));

        assertThatCode(() -> TestRunAssertions.assertPassed(run)).doesNotThrowAnyException();
        assertThatCode(() -> TestRunAssertions.assertCertifiable(run)).doesNotThrowAnyException();
        assertThatCode(() -> TestRunAssertions.assertFixturesSatisfied(run)).doesNotThrowAnyException();
        assertThatCode(() -> TestRunAssertions.assertNoRealInvocations(run)).doesNotThrowAnyException();
        assertThat(run.nodeTraces()).singleElement().satisfies(node -> {
            assertThat(node.occurrence()).isZero();
            assertThat(node.graphOccurrence()).isZero();
            assertThat(node.attempts()).isEmpty();
        });
        assertThat(run.edgeTraces()).isEmpty();
    }

    @Test
    void reportsStableRunAndRuleReferencesInsteadOfPayloads() throws Exception {
        TestRun run = TestRun.from(JSON.readTree(run("ASSERTION_FAILED", "EXPLORATORY", "SUCCESS", false)));

        assertThatThrownBy(() -> TestRunAssertions.assertPassed(run))
                .isInstanceOf(AssertionFailedError.class)
                .hasMessageContaining("run-7")
                .hasMessageContaining("ASSERTION_FAILED")
                .hasMessageNotContaining("private-payload");
        assertThatThrownBy(() -> TestRunAssertions.assertFixturesSatisfied(run))
                .isInstanceOf(AssertionFailedError.class)
                .hasMessageContaining("fixture-a");
        assertThatThrownBy(() -> TestRunAssertions.assertNoRealInvocations(run))
                .isInstanceOf(AssertionFailedError.class)
                .hasMessageContaining("node-a");
    }

    @Test
    void rejectsNegativeOccurrenceAndAttemptCoordinates() {
        assertThatThrownBy(() -> new TestRun.NodeTrace("node", "operator", "SUCCESS", "REAL",
                "", 1, "/root/node#primary", "/root", "", -1, 1, java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestRun.AttemptTrace(-1, "FAILED", "REAL", "ERROR", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestRun.EdgeTrace("a->b", "TRANSFERRED", "/root", "", -1,
                "/root/a#primary", "/root/b#primary"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String run(String status, String evidenceClass, String nodeStatus, boolean fixturePassed) {
        return """
                {"schemaVersion":"bloge.testExecutionResponse.v1","runId":"run-7",
                 "evidence":{"status":"%s","evidenceClass":"%s","targetFingerprint":"sha256:target",
                   "fixtureBundleFingerprint":"sha256:fixture","planFingerprint":"sha256:plan",
                   "nodeTrace":[{"nodeId":"node-a","operatorRef":"operator-a","status":"%s",
                   "fidelity":"%s","input":"private-payload","output":"private-payload"}],
                   "fixtureConsumptions":[{"ruleId":"fixture-a","uses":0,"required":true,"status":"%s"}],
                   "assertionResults":[{"scope":"OUTPUT_PATH","path":"/approved","passed":%s,
                     "diagnostic":"value differed"}],"diagnostics":["bounded diagnostic"]}}
                """.formatted(status, evidenceClass, nodeStatus,
                "SUCCESS".equals(nodeStatus) ? "REAL" : "OUTPUT_LEVEL",
                fixturePassed ? "SATISFIED" : "UNUSED", fixturePassed);
    }
}
