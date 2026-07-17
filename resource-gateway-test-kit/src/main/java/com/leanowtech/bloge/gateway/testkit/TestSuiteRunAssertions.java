package com.leanowtech.bloge.gateway.testkit;

import org.opentest4j.AssertionFailedError;

import java.util.List;

/** JUnit 5 assertion adapter for immutable suite execution and promotion-policy evidence. */
public final class TestSuiteRunAssertions {

    private TestSuiteRunAssertions() {
    }

    /**
     * Requires aggregate execution, every case, and declared coverage to pass.
     *
     * @param run suite run to assert
     */
    public static void assertPassed(TestSuiteRun run) {
        required(run);
        if (!run.passed()) {
            throw new AssertionFailedError("Resource Gateway business suite " + run.suiteRunId()
                    + " did not pass; mode=" + run.evaluationMode() + ", status=" + run.status()
                    + ", coverage=" + run.coverageStatus(),
                    "PASSED with SATISFIED coverage", run.status() + " with " + run.coverageStatus());
        }
    }

    /**
     * Requires exact reviewed cases to match the shared schema validator without claiming business
     * target execution, structural coverage, or promotion eligibility.
     *
     * @param run suite run to assert
     */
    public static void assertAdmissionPassed(TestSuiteRun run) {
        required(run);
        if (!run.admissionPassed()) {
            String coverage = run.admissionCoverage()
                    .map(value -> value.status().name()).orElse("UNAVAILABLE");
            throw new AssertionFailedError("Resource Gateway schema-admission suite "
                    + run.suiteRunId() + " did not pass; mode=" + run.evaluationMode()
                    + ", status=" + run.status() + ", admissionCoverage=" + coverage,
                    "SCHEMA_ADMISSION PASSED with SATISFIED admission coverage",
                    run.status() + " with " + coverage);
        }
    }

    /**
     * Requires every frozen property root and shrink input to satisfy its governed assertions.
     * This is a bounded sampled claim and never an exhaustive proof over the input schema.
     *
     * @param run suite run to assert
     */
    public static void assertPropertySatisfied(TestSuiteRun run) {
        required(run);
        if (!run.propertyPassed()) {
            String coverage = run.propertyCoverage()
                    .map(value -> value.status().name()).orElse("UNAVAILABLE");
            throw new AssertionFailedError("Resource Gateway property suite "
                    + run.suiteRunId() + " was not satisfied; mode=" + run.evaluationMode()
                    + ", status=" + run.status() + ", propertyCoverage=" + coverage,
                    "PROPERTY_EXECUTION PASSED with SATISFIED bounded coverage",
                    run.status() + " with " + coverage);
        }
    }

    /**
     * Requires at least one path-local counterexample and returns its payload-free coordinate.
     *
     * @param run bounded-property suite run
     * @return first counterexample in immutable trial order
     */
    public static TestSuiteRun.CounterexampleRef assertCounterexampleFound(TestSuiteRun run) {
        required(run);
        List<TestSuiteRun.CounterexampleRef> counterexamples =
                run.minimalObservedCounterexamples();
        if (counterexamples.isEmpty()) {
            String coverage = run.propertyCoverage()
                    .map(value -> value.status().name()).orElse("UNAVAILABLE");
            throw new AssertionFailedError("Resource Gateway property suite "
                    + run.suiteRunId() + " has no observed counterexample; propertyCoverage="
                    + coverage, "at least one path-local counterexample", coverage);
        }
        return counterexamples.getFirst();
    }

    /**
     * Requires every suite case to link a passing child run.
     *
     * @param run suite run to assert
     */
    public static void assertAllCasesPassed(TestSuiteRun run) {
        required(run);
        List<String> failed = run.caseResults().stream()
                .filter(result -> !result.passed())
                .map(result -> result.caseId() + "=" + result.status())
                .toList();
        if (!failed.isEmpty()) {
            throw new AssertionFailedError("Resource Gateway suite " + run.suiteRunId()
                    + " has non-passing cases: " + String.join(", ", failed), "all cases passed", failed);
        }
    }

    /**
     * Requires structural coverage to satisfy the immutable suite policy.
     *
     * @param run suite run to assert
     */
    public static void assertCoverageSatisfied(TestSuiteRun run) {
        required(run);
        if (run.coverageStatus() != TestSuiteRun.CoverageStatus.SATISFIED) {
            throw new AssertionFailedError("Resource Gateway suite " + run.suiteRunId()
                    + " did not satisfy coverage", TestSuiteRun.CoverageStatus.SATISFIED,
                    run.coverageStatus());
        }
    }

    /**
     * Requires policy eligibility for submission to a later release gate.
     * This assertion does not claim signature, certification, approval, or publication.
     *
     * @param run suite run to assert
     */
    public static void assertPromotionEligible(TestSuiteRun run) {
        required(run);
        if (!run.promotionEligible()) {
            throw new AssertionFailedError("Resource Gateway suite " + run.suiteRunId()
                    + " is not promotion eligible; reasons=" + String.join(",", run.promotionReasons()),
                    TestSuiteRun.PromotionStatus.ELIGIBLE, run.promotionStatus());
        }
    }

    private static void required(TestSuiteRun run) {
        if (run == null) {
            throw new AssertionFailedError("A Resource Gateway suite run is required", "non-null", null);
        }
    }
}
