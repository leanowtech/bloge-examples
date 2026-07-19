package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityHistoryWindow;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityTrendAnalysisRequest;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence.CaseSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence.CaseTrend;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence.CaseTrendStatus;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence.CorrelationSignal;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence.CorrelationSignalType;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence.RunObservation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure deterministic evaluator for retained exact-suite stability history.
 *
 * <p>All source records must be independently verified before they reach this evaluator. The
 * evaluator fingerprints only payload-free evidence coordinates and never infers a causal owner
 * from coincident behavior.</p>
 */
public final class TestSuiteStabilityTrendEvidenceEvaluator {
    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper canonical protocol mapper
     */
    public TestSuiteStabilityTrendEvidenceEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Derives one complete trend projection from a persistence-authoritative source window.
     *
     * @param tenantId verified tenant identity used only for deterministic id separation
     * @param environmentId verified environment identity used only for deterministic id separation
     * @param request exact bounded trend intent
     * @param requestFingerprint canonical request identity
     * @param window persistence-authoritative source window
     * @return immutable derived trend evidence
     */
    public TestSuiteStabilityTrendEvidence evaluate(
            String tenantId,
            String environmentId,
            TestSuiteStabilityTrendAnalysisRequest request,
            String requestFingerprint,
            TestSuiteStabilityHistoryWindow window) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(window, "window");
        List<RunObservation> sources = window.records().stream()
                .map(this::source)
                .toList();
        List<CaseTrend> caseTrends = caseTrends(sources);
        List<CorrelationSignal> signals = correlationSignals(sources);
        List<String> diagnostics = diagnostics(request, window, sources);
        boolean complete = window.complete();
        TestSuiteStabilityTrendEvidence.Status status = status(
                request, complete, sources, caseTrends);
        AnalysisIdentity identity = new AnalysisIdentity(
                TestSuiteStabilityTrendEvidence.SCHEMA_VERSION,
                normalized(tenantId), normalized(environmentId), requestFingerprint,
                sources.stream().map(value -> new SourceIdentity(
                        value.stabilityRunId(), value.evidenceFingerprint(),
                        value.attestationFingerprint())).toList(),
                window.expiredMatchingRuns(), window.truncated());
        String trendId = "stability-trend-"
                + ProtocolFingerprint.of(objectMapper, identity).substring("sha256:".length());
        return new TestSuiteStabilityTrendEvidence(
                TestSuiteStabilityTrendEvidence.SCHEMA_VERSION,
                trendId, requestFingerprint, request.suiteRef(),
                request.fromInclusive(), request.toExclusive(), request.minimumRuns(),
                request.maximumRuns(), sources.size(), window.expiredMatchingRuns(), complete,
                status, sources, caseTrends, signals,
                TestSuiteStabilityTrendEvidence.CausalityStatus.NOT_PROVEN,
                diagnostics, request.toExclusive());
    }

    private RunObservation source(TestSuiteStabilityRunRecord record) {
        TestSuiteStabilityEvidence evidence = record.evidence();
        List<CaseSnapshot> cases = evidence.caseResults().stream()
                .map(this::caseSnapshot)
                .sorted(Comparator.comparing(CaseSnapshot::caseId))
                .toList();
        String regimeFingerprint = ProtocolFingerprint.of(objectMapper,
                new RegimeMaterial(evidence.suiteRef().fingerprint(),
                        evidence.target().fingerprint(), cases.stream()
                        .map(value -> new CaseRegime(value.caseId(),
                                value.fixtureSetFingerprint(), value.planSetFingerprint()))
                        .toList()));
        return new RunObservation(
                record.stabilityRunId(), record.evidenceFingerprint(),
                ProtocolFingerprint.of(objectMapper, record.attestation()),
                evidence.schemaVersion(), evidence.target().fingerprint(), evidence.status(),
                evidence.promotion().status(), evidence.quarantine().status(),
                evidence.statisticalAssessment() == null ? null
                        : evidence.statisticalAssessment().status(),
                regimeFingerprint, cases, evidence.startedAt(), evidence.completedAt(),
                record.createdAt());
    }

    private CaseSnapshot caseSnapshot(TestSuiteStabilityEvidence.CaseStabilityResult value) {
        List<String> outcomes = value.observations().stream()
                .filter(observation -> observation.status()
                        == TestSuiteStabilityEvidence.ObservationStatus.VERIFIED)
                .map(TestSuiteStabilityEvidence.CaseObservation::outcomeIdentity)
                .distinct().sorted().toList();
        List<String> fixtures = value.observations().stream()
                .filter(observation -> observation.status()
                        == TestSuiteStabilityEvidence.ObservationStatus.VERIFIED)
                .map(TestSuiteStabilityEvidence.CaseObservation::fixtureBundleFingerprint)
                .distinct().sorted().toList();
        List<String> plans = value.observations().stream()
                .filter(observation -> observation.status()
                        == TestSuiteStabilityEvidence.ObservationStatus.VERIFIED)
                .map(TestSuiteStabilityEvidence.CaseObservation::planFingerprint)
                .distinct().sorted().toList();
        return new CaseSnapshot(value.caseId(), value.status(),
                ProtocolFingerprint.of(objectMapper, outcomes),
                ProtocolFingerprint.of(objectMapper, fixtures),
                ProtocolFingerprint.of(objectMapper, plans));
    }

    private static List<CaseTrend> caseTrends(List<RunObservation> sources) {
        Map<String, List<CasePoint>> byCase = new LinkedHashMap<>();
        for (RunObservation source : sources) {
            for (CaseSnapshot snapshot : source.cases()) {
                byCase.computeIfAbsent(snapshot.caseId(), ignored -> new ArrayList<>())
                        .add(new CasePoint(source.stabilityRunId(), source.regimeFingerprint(),
                                snapshot));
            }
        }
        return byCase.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> trend(entry.getKey(), entry.getValue(), sources.size()))
                .toList();
    }

    private static CaseTrend trend(String caseId, List<CasePoint> points, int sourceCount) {
        Set<String> regimes = new LinkedHashSet<>();
        List<String> changed = new ArrayList<>();
        CasePoint previous = null;
        for (CasePoint point : points) {
            regimes.add(caseRegime(point));
            if (previous != null && previous.runRegime().equals(point.runRegime())
                    && !previous.snapshot().outcomeSetFingerprint()
                    .equals(point.snapshot().outcomeSetFingerprint())) {
                changed.add(point.runId());
            }
            previous = point;
        }
        CaseTrendStatus status;
        if (points.size() != sourceCount || points.stream().anyMatch(value ->
                value.snapshot().status() == TestSuiteStabilityEvidence.CaseStatus.INCONCLUSIVE)) {
            status = CaseTrendStatus.INCONCLUSIVE;
        } else if (points.stream().anyMatch(value ->
                value.snapshot().status() == TestSuiteStabilityEvidence.CaseStatus.FLAKY)
                || !changed.isEmpty()) {
            status = CaseTrendStatus.INSTABILITY_OBSERVED;
        } else if (regimes.size() > 1) {
            status = CaseTrendStatus.REGIME_DRIFT_OBSERVED;
        } else if (points.stream().anyMatch(value -> value.snapshot().status()
                == TestSuiteStabilityEvidence.CaseStatus.CONSISTENT_FAILURE)) {
            status = CaseTrendStatus.CONSISTENT_FAILURE_OBSERVED;
        } else {
            status = CaseTrendStatus.STABLE_PASS;
        }
        return new CaseTrend(caseId, status,
                points.stream().map(CasePoint::runId).toList(), changed, regimes.size());
    }

    private static List<CorrelationSignal> correlationSignals(List<RunObservation> sources) {
        List<CorrelationSignal> result = new ArrayList<>();
        for (RunObservation source : sources) {
            List<String> flaky = source.cases().stream()
                    .filter(value -> value.status() == TestSuiteStabilityEvidence.CaseStatus.FLAKY)
                    .map(CaseSnapshot::caseId).sorted().toList();
            if (flaky.size() >= 2) {
                result.add(new CorrelationSignal(CorrelationSignalType.MULTI_CASE_FLAKINESS,
                        "", source.stabilityRunId(), source.regimeFingerprint(), flaky));
            }
        }
        for (int index = 1; index < sources.size(); index++) {
            RunObservation previous = sources.get(index - 1);
            RunObservation current = sources.get(index);
            if (!previous.regimeFingerprint().equals(current.regimeFingerprint())) {
                continue;
            }
            Map<String, CaseSnapshot> previousCases = byCase(previous.cases());
            List<String> shifted = current.cases().stream()
                    .filter(value -> previousCases.containsKey(value.caseId()))
                    .filter(value -> conclusive(value)
                            && conclusive(previousCases.get(value.caseId())))
                    .filter(value -> !value.outcomeSetFingerprint().equals(
                            previousCases.get(value.caseId()).outcomeSetFingerprint()))
                    .map(CaseSnapshot::caseId).sorted().toList();
            if (shifted.size() >= 2) {
                result.add(new CorrelationSignal(CorrelationSignalType.COINCIDENT_OUTCOME_SHIFT,
                        previous.stabilityRunId(), current.stabilityRunId(),
                        current.regimeFingerprint(), shifted));
            }
        }
        return List.copyOf(result);
    }

    private static TestSuiteStabilityTrendEvidence.Status status(
            TestSuiteStabilityTrendAnalysisRequest request,
            boolean complete,
            List<RunObservation> sources,
            List<CaseTrend> trends) {
        if (!complete || sources.size() < request.minimumRuns()
                || sources.stream().anyMatch(value ->
                value.status() == TestSuiteStabilityEvidence.Status.INCONCLUSIVE)
                || trends.stream().anyMatch(value ->
                value.status() == CaseTrendStatus.INCONCLUSIVE)) {
            return TestSuiteStabilityTrendEvidence.Status.INCONCLUSIVE;
        }
        if (sources.stream().anyMatch(value ->
                value.status() == TestSuiteStabilityEvidence.Status.FLAKY)
                || trends.stream().anyMatch(value ->
                value.status() == CaseTrendStatus.INSTABILITY_OBSERVED)) {
            return TestSuiteStabilityTrendEvidence.Status.INSTABILITY_OBSERVED;
        }
        if (sources.stream().map(RunObservation::regimeFingerprint).distinct().count() > 1
                || trends.stream().anyMatch(value ->
                value.status() == CaseTrendStatus.REGIME_DRIFT_OBSERVED)) {
            return TestSuiteStabilityTrendEvidence.Status.REGIME_DRIFT_OBSERVED;
        }
        if (sources.stream().anyMatch(value -> value.status()
                == TestSuiteStabilityEvidence.Status.CONSISTENT_FAILURE)
                || trends.stream().anyMatch(value -> value.status()
                == CaseTrendStatus.CONSISTENT_FAILURE_OBSERVED)) {
            return TestSuiteStabilityTrendEvidence.Status.CONSISTENT_FAILURE_OBSERVED;
        }
        return TestSuiteStabilityTrendEvidence.Status.STABLE_PASS;
    }

    private static List<String> diagnostics(
            TestSuiteStabilityTrendAnalysisRequest request,
            TestSuiteStabilityHistoryWindow window,
            List<RunObservation> sources) {
        Set<String> values = new LinkedHashSet<>();
        if (window.expiredMatchingRuns() > 0) {
            values.add("SOURCE_RETENTION_GAP");
        }
        if (window.truncated()) {
            values.add("SOURCE_WINDOW_TRUNCATED");
        }
        if (sources.size() < request.minimumRuns()) {
            values.add("MINIMUM_RUNS_NOT_MET");
        }
        if (sources.stream().anyMatch(value ->
                value.status() == TestSuiteStabilityEvidence.Status.INCONCLUSIVE)) {
            values.add("SOURCE_INCONCLUSIVE");
        }
        return values.stream().sorted().toList();
    }

    private static Map<String, CaseSnapshot> byCase(List<CaseSnapshot> values) {
        Map<String, CaseSnapshot> result = new LinkedHashMap<>();
        values.forEach(value -> result.put(value.caseId(), value));
        return result;
    }

    private static boolean conclusive(CaseSnapshot value) {
        return value.status() != TestSuiteStabilityEvidence.CaseStatus.INCONCLUSIVE;
    }

    private static String caseRegime(CasePoint value) {
        return value.snapshot().fixtureSetFingerprint() + ':'
                + value.snapshot().planSetFingerprint();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record CasePoint(String runId, String runRegime, CaseSnapshot snapshot) {
    }

    private record RegimeMaterial(
            String suiteFingerprint,
            String targetFingerprint,
            List<CaseRegime> cases) {
    }

    private record CaseRegime(
            String caseId,
            String fixtureSetFingerprint,
            String planSetFingerprint) {
    }

    private record SourceIdentity(
            String stabilityRunId,
            String evidenceFingerprint,
            String attestationFingerprint) {
    }

    private record AnalysisIdentity(
            String schemaVersion,
            String tenantId,
            String environmentId,
            String requestFingerprint,
            List<SourceIdentity> sources,
            int expiredMatchingRuns,
            boolean truncated) {
    }
}
