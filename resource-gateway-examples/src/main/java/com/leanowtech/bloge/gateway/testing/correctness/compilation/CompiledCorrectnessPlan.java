package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistrationRequest;

import java.util.List;
import java.util.Objects;

/** Internal payload-bearing plan. Never return, persist, log, or publish this value. */
final class CompiledCorrectnessPlan {

    private final CorrectnessCompilationReport report;
    private final List<FixtureBundleRegistrationRequest> fixtureRegistrations;
    private final TestSuiteRegistrationRequest suiteRegistration;

    CompiledCorrectnessPlan(
            CorrectnessCompilationReport report,
            List<FixtureBundleRegistrationRequest> fixtureRegistrations,
            TestSuiteRegistrationRequest suiteRegistration
    ) {
        this.report = Objects.requireNonNull(report, "report");
        this.fixtureRegistrations = fixtureRegistrations == null
                ? List.of() : List.copyOf(fixtureRegistrations);
        this.suiteRegistration = suiteRegistration;
        if (report.publishable()
                && (this.fixtureRegistrations.isEmpty() || suiteRegistration == null)) {
            throw new IllegalArgumentException(
                    "Publishable plan requires Fixture and Test Suite registrations");
        }
        if (!report.publishable()
                && (!this.fixtureRegistrations.isEmpty() || suiteRegistration != null)) {
            throw new IllegalArgumentException(
                    "Blocked plan must not retain partial payload-bearing registrations");
        }
    }

    CorrectnessCompilationReport report() {
        return report;
    }

    List<FixtureBundleRegistrationRequest> fixtureRegistrations() {
        return fixtureRegistrations;
    }

    TestSuiteRegistrationRequest suiteRegistration() {
        return suiteRegistration;
    }

    @Override
    public String toString() {
        return "CompiledCorrectnessPlan[report=" + report
                + ", fixtureRegistrationCount=" + fixtureRegistrations.size()
                + ", suiteRegistration=" + (suiteRegistration == null ? "ABSENT" : "PROTECTED")
                + ']';
    }
}
