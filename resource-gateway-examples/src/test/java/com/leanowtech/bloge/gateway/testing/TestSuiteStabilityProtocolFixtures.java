package com.leanowtech.bloge.gateway.testing;

import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared payload-free stability protocol fixtures for persistence and signature tests. */
public final class TestSuiteStabilityProtocolFixtures {
    /** Exact suite fingerprint used by the fixture. */
    public static final String SUITE_FINGERPRINT = fingerprint('a');
    /** Exact target fingerprint used by the fixture. */
    public static final String TARGET_FINGERPRINT = fingerprint('b');
    /** Exact fixture fingerprint used by the fixture. */
    public static final String FIXTURE_FINGERPRINT = fingerprint('c');
    /** Exact plan fingerprint used by the fixture. */
    public static final String PLAN_FINGERPRINT = fingerprint('d');
    /** Deterministic stability analysis id used by the fixture. */
    public static final String STABILITY_RUN_ID = "stability-" + "e".repeat(64);
    /** Exact immutable suite reference used by the fixture. */
    public static final TestSuiteExecutionRequest.SuiteRef SUITE_REF =
            new TestSuiteExecutionRequest.SuiteRef("suite-a", 3, SUITE_FINGERPRINT);
    /** Exact target used by the fixture. */
    public static final TestSuite.Target TARGET =
            new TestSuite.Target("GRAPH", "graph-a", TARGET_FINGERPRINT);
    private static final Instant START = Instant.parse("2026-07-18T02:00:00Z");

    private TestSuiteStabilityProtocolFixtures() {
    }

    /**
     * Creates internally consistent stable evidence with three independent source runs.
     *
     * @return immutable stable evidence
     */
    public static TestSuiteStabilityEvidence stableEvidence() {
        TestSuite.FixtureBundleRef fixture = new TestSuite.FixtureBundleRef(
                "fixture-a", 2, FIXTURE_FINGERPRINT);
        List<TestSuiteStabilityEvidence.AttemptResult> attempts = new ArrayList<>();
        List<TestSuiteStabilityEvidence.CaseObservation> observations = new ArrayList<>();
        for (int attempt = 1; attempt <= 3; attempt++) {
            attempts.add(new TestSuiteStabilityEvidence.AttemptResult(attempt,
                    TestSuiteStabilityEvidence.AttemptStatus.VERIFIED,
                    "suite-run-" + attempt, indexedFingerprint(attempt),
                    TestSuiteRunEvidence.Status.PASSED, START.plusSeconds(attempt),
                    START.plusSeconds(attempt + 1L), ""));
            observations.add(new TestSuiteStabilityEvidence.CaseObservation(attempt,
                    TestSuiteStabilityEvidence.ObservationStatus.VERIFIED,
                    "child-run-" + attempt, indexedFingerprint(10 + attempt),
                    TestRunEvidence.Status.PASSED,
                    TestRunEvidence.EvidenceClass.CERTIFIABLE,
                    FIXTURE_FINGERPRINT, PLAN_FINGERPRINT, fingerprint('f'), ""));
        }
        TestSuiteStabilityEvidence.CaseStabilityResult result =
                new TestSuiteStabilityEvidence.CaseStabilityResult(
                        "golden", TestSuite.CaseType.GOLDEN, fixture,
                        TestSuiteStabilityEvidence.CaseStatus.STABLE_PASS,
                        observations, 1, List.of());
        List<TestSuiteStabilityEvidence.CaseStabilityResult> cases = List.of(result);
        TestSuiteStabilityEvidence.Status status = TestSuiteStabilityEvidence.Status.STABLE;
        return new TestSuiteStabilityEvidence("", STABILITY_RUN_ID, "stability-request",
                SUITE_REF, TARGET, 3, status, attempts, cases,
                TestSuiteStabilityEvidence.derivePromotion(attempts, cases, status),
                TestSuiteStabilityEvidence.deriveQuarantine(cases, status),
                START.plusSeconds(1), START.plusSeconds(4), List.of(),
                Map.of("pipeline", "nightly"));
    }

    /** @return canonical test fingerprint filled with one hexadecimal character */
    public static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static String indexedFingerprint(int value) {
        return "sha256:" + "%064x".formatted(value);
    }
}
