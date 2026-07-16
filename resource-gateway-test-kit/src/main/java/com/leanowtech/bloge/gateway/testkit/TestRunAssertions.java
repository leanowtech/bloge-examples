package com.leanowtech.bloge.gateway.testkit;

import org.opentest4j.AssertionFailedError;

import java.util.List;

/** JUnit 5 assertion adapter for release-gate and contract-test expectations. */
public final class TestRunAssertions {

    private TestRunAssertions() {
    }

    /**
     * Requires the run to terminate in {@link TestRun.Status#PASSED}.
     * @param run run to assert
     */
    public static void assertPassed(TestRun run) {
        required(run);
        if (!run.passed()) {
            throw new AssertionFailedError("Resource Gateway run " + run.runId()
                    + " did not pass; status=" + run.status(), TestRun.Status.PASSED, run.status());
        }
    }

    /**
     * Requires evidence eligible for correctness workbook or publish-gate consumption.
     * @param run run to assert
     */
    public static void assertCertifiable(TestRun run) {
        required(run);
        if (run.evidenceClass() != TestRun.EvidenceClass.CERTIFIABLE) {
            throw new AssertionFailedError("Resource Gateway run " + run.runId()
                    + " is not certifiable", TestRun.EvidenceClass.CERTIFIABLE, run.evidenceClass());
        }
    }

    /**
     * Requires every declared fixture-consumption fact to be satisfied.
     * @param run run to assert
     */
    public static void assertFixturesSatisfied(TestRun run) {
        required(run);
        List<String> unsatisfied = run.fixtureConsumptions().stream()
                .filter(consumption -> !consumption.satisfied())
                .map(consumption -> consumption.ruleId() + "=" + consumption.status())
                .toList();
        if (!unsatisfied.isEmpty()) {
            throw new AssertionFailedError("Resource Gateway run " + run.runId()
                    + " has unsatisfied fixtures: " + String.join(", ", unsatisfied), "all satisfied", unsatisfied);
        }
    }

    /**
     * Requires that no invocation escaped fixture control into real execution. Use this in suites
     * that must be hermetic; SPY/REAL-oriented suites should omit the assertion explicitly.
     * @param run run to assert
     */
    public static void assertNoRealInvocations(TestRun run) {
        required(run);
        List<String> real = run.nodeTraces().stream()
                .filter(node -> "REAL".equals(node.fidelity()) && !"SKIPPED".equals(node.status()))
                .map(node -> node.nodeId() + "(" + node.operatorRef() + ")")
                .toList();
        if (!real.isEmpty()) {
            throw new AssertionFailedError("Resource Gateway run " + run.runId()
                    + " executed real invocation sites: " + String.join(", ", real), "no real invocations", real);
        }
    }

    /**
     * Requires two runs to carry the same non-empty deterministic business-result identity.
     *
     * @param expected baseline run
     * @param actual repeated or comparison run
     */
    public static void assertSameSemanticResult(TestRun expected, TestRun actual) {
        required(expected);
        required(actual);
        if (expected.semanticResultFingerprint().isBlank()) {
            throw new AssertionFailedError("Baseline Resource Gateway run has no semantic result fingerprint",
                    "non-empty semantic result fingerprint", "");
        }
        if (!expected.semanticResultFingerprint().equals(actual.semanticResultFingerprint())) {
            throw new AssertionFailedError("Resource Gateway runs have different semantic results",
                    expected.semanticResultFingerprint(), actual.semanticResultFingerprint());
        }
    }

    private static void required(TestRun run) {
        if (run == null) {
            throw new AssertionFailedError("A Resource Gateway test run is required", "non-null", null);
        }
    }
}
