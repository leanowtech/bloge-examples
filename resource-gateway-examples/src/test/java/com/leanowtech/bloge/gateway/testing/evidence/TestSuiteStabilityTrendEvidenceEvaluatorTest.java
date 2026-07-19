package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityTrendProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityTrendProtocolFixtures.CaseMode;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityHistoryWindow;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityTrendAnalysisRequest;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence.CaseTrendStatus;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence.CorrelationSignalType;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestSuiteStabilityTrendEvidenceEvaluatorTest {
    private static final Instant FROM = Instant.parse("2026-07-18T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-07-19T00:00:00Z");

    private ObjectMapper mapper;
    private TestSuiteStabilityAttestationService sourceAttestations;
    private TestSuiteStabilityTrendEvidenceEvaluator evaluator;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        sourceAttestations = new TestSuiteStabilityAttestationService(
                mapper, new InMemoryVisualEvidenceSigner());
        evaluator = new TestSuiteStabilityTrendEvidenceEvaluator(mapper);
    }

    @Test
    void stableSourcesInOneRegimeProduceAStableNonCausalProjection() {
        TestSuiteStabilityRunRecord first = source('1', FROM.plusSeconds(100),
                TestSuiteStabilityProtocolFixtures.PLAN_FINGERPRINT,
                CaseMode.STABLE, CaseMode.STABLE, '1', '2');
        TestSuiteStabilityRunRecord second = source('2', FROM.plusSeconds(200),
                TestSuiteStabilityProtocolFixtures.PLAN_FINGERPRINT,
                CaseMode.STABLE, CaseMode.STABLE, '1', '2');

        TestSuiteStabilityTrendEvidence evidence = evaluate(List.of(first, second), 0, false);

        assertThat(evidence.status()).isEqualTo(
                TestSuiteStabilityTrendEvidence.Status.STABLE_PASS);
        assertThat(evidence.completeWindow()).isTrue();
        assertThat(evidence.caseTrends()).allSatisfy(value ->
                assertThat(value.status()).isEqualTo(CaseTrendStatus.STABLE_PASS));
        assertThat(evidence.correlationSignals()).isEmpty();
        assertThat(evidence.causalityStatus()).isEqualTo(
                TestSuiteStabilityTrendEvidence.CausalityStatus.NOT_PROVEN);
    }

    @Test
    void sameRegimeCoincidentOutcomeShiftIsInstabilityAndOnlyACorrelationSignal() {
        TestSuiteStabilityRunRecord first = source('1', FROM.plusSeconds(100),
                TestSuiteStabilityProtocolFixtures.PLAN_FINGERPRINT,
                CaseMode.STABLE, CaseMode.STABLE, '1', '2');
        TestSuiteStabilityRunRecord second = source('2', FROM.plusSeconds(200),
                TestSuiteStabilityProtocolFixtures.PLAN_FINGERPRINT,
                CaseMode.STABLE, CaseMode.STABLE, '3', '4');

        TestSuiteStabilityTrendEvidence evidence = evaluate(List.of(first, second), 0, false);

        assertThat(evidence.status()).isEqualTo(
                TestSuiteStabilityTrendEvidence.Status.INSTABILITY_OBSERVED);
        assertThat(evidence.caseTrends()).allSatisfy(value -> {
            assertThat(value.status()).isEqualTo(CaseTrendStatus.INSTABILITY_OBSERVED);
            assertThat(value.changedAtRunIds()).containsExactly(second.stabilityRunId());
        });
        assertThat(evidence.correlationSignals()).singleElement().satisfies(signal -> {
            assertThat(signal.type()).isEqualTo(
                    CorrelationSignalType.COINCIDENT_OUTCOME_SHIFT);
            assertThat(signal.previousRunId()).isEqualTo(first.stabilityRunId());
            assertThat(signal.currentRunId()).isEqualTo(second.stabilityRunId());
            assertThat(signal.caseIds()).containsExactly("case-a", "case-b");
        });
    }

    @Test
    void planChangeCreatesARegimeBoundaryAndSuppressesOutcomeShiftCorrelation() {
        TestSuiteStabilityRunRecord first = source('1', FROM.plusSeconds(100),
                TestSuiteStabilityProtocolFixtures.PLAN_FINGERPRINT,
                CaseMode.STABLE, CaseMode.STABLE, '1', '2');
        TestSuiteStabilityRunRecord second = source('2', FROM.plusSeconds(200),
                TestSuiteStabilityProtocolFixtures.fingerprint('9'),
                CaseMode.STABLE, CaseMode.STABLE, '3', '4');

        TestSuiteStabilityTrendEvidence evidence = evaluate(List.of(first, second), 0, false);

        assertThat(evidence.status()).isEqualTo(
                TestSuiteStabilityTrendEvidence.Status.REGIME_DRIFT_OBSERVED);
        assertThat(evidence.caseTrends()).allSatisfy(value -> {
            assertThat(value.status()).isEqualTo(CaseTrendStatus.REGIME_DRIFT_OBSERVED);
            assertThat(value.changedAtRunIds()).isEmpty();
            assertThat(value.regimeCount()).isEqualTo(2);
        });
        assertThat(evidence.correlationSignals()).isEmpty();
    }

    @Test
    void multipleFlakyCasesProduceABoundedWithinRunSignalWithoutCausalClaim() {
        TestSuiteStabilityRunRecord first = source('1', FROM.plusSeconds(100),
                TestSuiteStabilityProtocolFixtures.PLAN_FINGERPRINT,
                CaseMode.STABLE, CaseMode.STABLE, '1', '2');
        TestSuiteStabilityRunRecord second = source('2', FROM.plusSeconds(200),
                TestSuiteStabilityProtocolFixtures.PLAN_FINGERPRINT,
                CaseMode.FLAKY, CaseMode.FLAKY, '1', '2');

        TestSuiteStabilityTrendEvidence evidence = evaluate(List.of(first, second), 0, false);

        assertThat(evidence.status()).isEqualTo(
                TestSuiteStabilityTrendEvidence.Status.INSTABILITY_OBSERVED);
        assertThat(evidence.correlationSignals()).anySatisfy(signal -> {
            assertThat(signal.type()).isEqualTo(CorrelationSignalType.MULTI_CASE_FLAKINESS);
            assertThat(signal.previousRunId()).isBlank();
            assertThat(signal.currentRunId()).isEqualTo(second.stabilityRunId());
            assertThat(signal.caseIds()).containsExactly("case-a", "case-b");
        });
    }

    @Test
    void retentionTruncationInsufficientDataAndSourceCensoringRemainInconclusive() {
        TestSuiteStabilityRunRecord source = source('1', FROM.plusSeconds(100),
                TestSuiteStabilityProtocolFixtures.PLAN_FINGERPRINT,
                CaseMode.INCONCLUSIVE, CaseMode.STABLE, '1', '2');

        TestSuiteStabilityTrendEvidence evidence = evaluate(List.of(source), 1, true);

        assertThat(evidence.status()).isEqualTo(
                TestSuiteStabilityTrendEvidence.Status.INCONCLUSIVE);
        assertThat(evidence.completeWindow()).isFalse();
        assertThat(evidence.diagnostics()).containsExactly(
                "MINIMUM_RUNS_NOT_MET", "SOURCE_INCONCLUSIVE",
                "SOURCE_RETENTION_GAP", "SOURCE_WINDOW_TRUNCATED");
    }

    @Test
    void invariantFailuresStayDistinctFromFlakiness() {
        TestSuiteStabilityRunRecord first = source('1', FROM.plusSeconds(100),
                TestSuiteStabilityProtocolFixtures.PLAN_FINGERPRINT,
                CaseMode.CONSISTENT_FAILURE, CaseMode.STABLE, '1', '2');
        TestSuiteStabilityRunRecord second = source('2', FROM.plusSeconds(200),
                TestSuiteStabilityProtocolFixtures.PLAN_FINGERPRINT,
                CaseMode.CONSISTENT_FAILURE, CaseMode.STABLE, '1', '2');

        TestSuiteStabilityTrendEvidence evidence = evaluate(List.of(first, second), 0, false);

        assertThat(evidence.status()).isEqualTo(
                TestSuiteStabilityTrendEvidence.Status.CONSISTENT_FAILURE_OBSERVED);
        assertThat(evidence.caseTrends()).filteredOn(value -> value.caseId().equals("case-a"))
                .singleElement().extracting(value -> value.status())
                .isEqualTo(CaseTrendStatus.CONSISTENT_FAILURE_OBSERVED);
    }

    private TestSuiteStabilityTrendEvidence evaluate(
            List<TestSuiteStabilityRunRecord> records,
            int expired,
            boolean truncated) {
        TestSuiteStabilityTrendAnalysisRequest request = request();
        return evaluator.evaluate("tenant-a", "test", request,
                ProtocolFingerprint.of(mapper, request),
                new TestSuiteStabilityHistoryWindow(records, expired, truncated, TO));
    }

    private TestSuiteStabilityRunRecord source(
            char id,
            Instant createdAt,
            String plan,
            CaseMode first,
            CaseMode second,
            char firstOutcome,
            char secondOutcome) {
        return TestSuiteStabilityTrendProtocolFixtures.record(
                mapper, sourceAttestations, id, createdAt, TO.plusSeconds(3_600),
                plan, first, second, firstOutcome, secondOutcome);
    }

    private static TestSuiteStabilityTrendAnalysisRequest request() {
        return new TestSuiteStabilityTrendAnalysisRequest("",
                TestSuiteStabilityProtocolFixtures.SUITE_REF,
                FROM, TO, 2, 10);
    }
}
