package com.leanowtech.bloge.gateway.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunRecord;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityAttestationService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared signed two-case fixtures for retained-window trend tests. */
public final class TestSuiteStabilityTrendProtocolFixtures {
    /** Supported per-case source shape. */
    public enum CaseMode {
        STABLE,
        FLAKY,
        CONSISTENT_FAILURE,
        INCONCLUSIVE
    }

    private TestSuiteStabilityTrendProtocolFixtures() {
    }

    /**
     * Creates one complete signed deterministic stability source.
     *
     * @param mapper canonical mapper
     * @param attestations source signature service
     * @param identity hexadecimal identity character
     * @param createdAt terminal persistence time
     * @param expiresAt evidence retention deadline
     * @param planFingerprint effective-plan identity shared by both cases
     * @param firstMode first case behavior
     * @param secondMode second case behavior
     * @param firstOutcome first stable outcome identity seed
     * @param secondOutcome second stable outcome identity seed
     * @return immutable signed terminal record
     */
    public static TestSuiteStabilityRunRecord record(
            ObjectMapper mapper,
            TestSuiteStabilityAttestationService attestations,
            char identity,
            Instant createdAt,
            Instant expiresAt,
            String planFingerprint,
            CaseMode firstMode,
            CaseMode secondMode,
            char firstOutcome,
            char secondOutcome) {
        String runId = "stability-" + String.valueOf(identity).repeat(64);
        Instant startedAt = createdAt.minusSeconds(20);
        boolean censored = firstMode == CaseMode.INCONCLUSIVE
                || secondMode == CaseMode.INCONCLUSIVE;
        List<TestSuiteStabilityEvidence.AttemptResult> attempts =
                attempts(identity, startedAt, censored);
        List<TestSuiteStabilityEvidence.CaseStabilityResult> cases = List.of(
                caseResult("case-a", firstMode, identity, startedAt,
                        planFingerprint, firstOutcome, 0, censored),
                caseResult("case-b", secondMode, identity, startedAt,
                        planFingerprint, secondOutcome, 10, censored));
        TestSuiteStabilityEvidence.Status status = censored
                ? TestSuiteStabilityEvidence.Status.INCONCLUSIVE
                : aggregate(firstMode, secondMode);
        TestSuiteStabilityEvidence evidence = new TestSuiteStabilityEvidence(
                TestSuiteStabilityEvidence.SCHEMA_VERSION_V2,
                runId, "trend-source-" + identity,
                TestSuiteStabilityProtocolFixtures.SUITE_REF,
                TestSuiteStabilityProtocolFixtures.TARGET,
                3, status, attempts, cases,
                TestSuiteStabilityEvidence.derivePromotion(attempts, cases, status),
                TestSuiteStabilityEvidence.deriveQuarantine(cases, status),
                null, startedAt, createdAt.minusSeconds(1), List.of(), Map.of());
        String requestFingerprint = ProtocolFingerprint.ofText("trend-source-request-" + identity);
        var seal = attestations.seal(evidence, requestFingerprint);
        if (!seal.verified()) {
            throw new IllegalStateException("Trend fixture source signature failed");
        }
        return new TestSuiteStabilityRunRecord(
                runId, evidence.clientRequestId(), requestFingerprint,
                "tenant-a", "org-a", "project-a", "test", "actor-a", "INTERNAL",
                ProtocolFingerprint.of(mapper, evidence), evidence, seal.attestation(),
                createdAt, expiresAt);
    }

    private static List<TestSuiteStabilityEvidence.AttemptResult> attempts(
            char identity,
            Instant startedAt,
            boolean censored) {
        List<TestSuiteStabilityEvidence.AttemptResult> result = new ArrayList<>();
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (censored && attempt == 3) {
                result.add(new TestSuiteStabilityEvidence.AttemptResult(
                        attempt, TestSuiteStabilityEvidence.AttemptStatus.INCONCLUSIVE,
                        "", "", null, null, List.of(),
                        startedAt.plusSeconds(attempt), startedAt.plusSeconds(attempt + 1L),
                        "SOURCE_INCOMPLETE"));
                continue;
            }
            result.add(new TestSuiteStabilityEvidence.AttemptResult(
                    attempt, TestSuiteStabilityEvidence.AttemptStatus.VERIFIED,
                    "suite-run-" + identity + '-' + attempt,
                    indexedFingerprint(identity, attempt), TestSuiteRunEvidence.Status.PASSED,
                    TestSuiteRunEvidence.PromotionStatus.ELIGIBLE, List.of(),
                    startedAt.plusSeconds(attempt), startedAt.plusSeconds(attempt + 1L), ""));
        }
        return result;
    }

    private static TestSuiteStabilityEvidence.CaseStabilityResult caseResult(
            String caseId,
            CaseMode mode,
            char identity,
            Instant startedAt,
            String planFingerprint,
            char outcome,
            int offset,
            boolean censored) {
        List<TestSuiteStabilityEvidence.CaseObservation> observations = new ArrayList<>();
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (censored && attempt == 3) {
                observations.add(new TestSuiteStabilityEvidence.CaseObservation(
                        attempt, TestSuiteStabilityEvidence.ObservationStatus.INCONCLUSIVE,
                        "", "", null, null, "", "", "", "SOURCE_INCOMPLETE"));
                continue;
            }
            char effectiveOutcome = mode == CaseMode.FLAKY && attempt == 3
                    ? nextHex(outcome) : outcome;
            TestRunEvidence.Status evidenceStatus = mode == CaseMode.CONSISTENT_FAILURE
                    ? TestRunEvidence.Status.ASSERTION_FAILED : TestRunEvidence.Status.PASSED;
            observations.add(new TestSuiteStabilityEvidence.CaseObservation(
                    attempt, TestSuiteStabilityEvidence.ObservationStatus.VERIFIED,
                    "child-" + identity + '-' + caseId + '-' + attempt,
                    indexedFingerprint(identity, 20 + offset + attempt), evidenceStatus,
                    TestRunEvidence.EvidenceClass.CERTIFIABLE,
                    TestSuiteStabilityProtocolFixtures.FIXTURE_FINGERPRINT,
                    planFingerprint,
                    TestSuiteStabilityProtocolFixtures.fingerprint(effectiveOutcome), ""));
        }
        TestSuiteStabilityEvidence.CaseStatus status = censored
                ? TestSuiteStabilityEvidence.CaseStatus.INCONCLUSIVE : switch (mode) {
            case STABLE -> TestSuiteStabilityEvidence.CaseStatus.STABLE_PASS;
            case FLAKY -> TestSuiteStabilityEvidence.CaseStatus.FLAKY;
            case CONSISTENT_FAILURE ->
                    TestSuiteStabilityEvidence.CaseStatus.CONSISTENT_FAILURE;
            case INCONCLUSIVE -> TestSuiteStabilityEvidence.CaseStatus.INCONCLUSIVE;
        };
        int outcomes = mode == CaseMode.FLAKY && !censored ? 2 : 1;
        return new TestSuiteStabilityEvidence.CaseStabilityResult(
                caseId, TestSuite.CaseType.REGRESSION,
                new TestSuite.FixtureBundleRef("fixture-" + caseId, 1,
                        TestSuiteStabilityProtocolFixtures.FIXTURE_FINGERPRINT),
                status, observations, outcomes,
                censored ? List.of("SOURCE_INCOMPLETE") : List.of());
    }

    private static TestSuiteStabilityEvidence.Status aggregate(
            CaseMode first,
            CaseMode second) {
        if (first == CaseMode.FLAKY || second == CaseMode.FLAKY) {
            return TestSuiteStabilityEvidence.Status.FLAKY;
        }
        if (first == CaseMode.INCONCLUSIVE || second == CaseMode.INCONCLUSIVE) {
            return TestSuiteStabilityEvidence.Status.INCONCLUSIVE;
        }
        if (first == CaseMode.CONSISTENT_FAILURE
                || second == CaseMode.CONSISTENT_FAILURE) {
            return TestSuiteStabilityEvidence.Status.CONSISTENT_FAILURE;
        }
        return TestSuiteStabilityEvidence.Status.STABLE;
    }

    private static String indexedFingerprint(char identity, int value) {
        int prefix = Character.digit(identity, 16);
        return "sha256:" + "%064x".formatted((long) prefix * 1_000 + value);
    }

    private static char nextHex(char value) {
        int digit = Character.digit(value, 16);
        return Character.forDigit((digit + 1) % 16, 16);
    }
}
