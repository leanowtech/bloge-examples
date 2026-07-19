package com.leanowtech.bloge.gateway.testkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared pure trend derivation used by retained-window and compact-range verifiers. */
final class TestSuiteStabilityTrendProjection {
    private TestSuiteStabilityTrendProjection() {
    }

    static Result project(
            List<TestSuiteStabilityTrendAnalysis.SourceObservation> sources,
            int minimumRuns,
            boolean complete,
            List<String> diagnostics) {
        List<TestSuiteStabilityTrendAnalysis.SourceObservation> exact = sources == null
                ? List.of() : List.copyOf(sources);
        if (minimumRuns < 2 || minimumRuns > 100 || exact.size() > 100) {
            throw new IllegalArgumentException("Bounded trend observations are required");
        }
        List<TestSuiteStabilityTrendAnalysis.CaseTrend> trends = caseTrends(exact);
        List<TestSuiteStabilityTrendAnalysis.CorrelationSignal> signals = signals(exact);
        Set<String> reasons = new LinkedHashSet<>(
                diagnostics == null ? List.of() : diagnostics);
        if (exact.size() < minimumRuns) {
            reasons.add("MINIMUM_RUNS_NOT_MET");
        }
        if (exact.stream().anyMatch(value ->
                value.status() == TestSuiteStabilityRun.Status.INCONCLUSIVE)) {
            reasons.add("SOURCE_INCONCLUSIVE");
        }
        return new Result(status(exact, minimumRuns, complete, trends), trends, signals,
                reasons.stream().sorted().toList());
    }

    private static List<TestSuiteStabilityTrendAnalysis.CaseTrend> caseTrends(
            List<TestSuiteStabilityTrendAnalysis.SourceObservation> sources) {
        Map<String, List<CasePoint>> byCase = new LinkedHashMap<>();
        for (TestSuiteStabilityTrendAnalysis.SourceObservation source : sources) {
            for (TestSuiteStabilityTrendAnalysis.CaseSnapshot snapshot : source.cases()) {
                byCase.computeIfAbsent(snapshot.caseId(), ignored -> new ArrayList<>())
                        .add(new CasePoint(source.stabilityRunId(), source.regimeFingerprint(),
                                snapshot));
            }
        }
        return byCase.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> caseTrend(entry.getKey(), entry.getValue(), sources.size()))
                .toList();
    }

    private static TestSuiteStabilityTrendAnalysis.CaseTrend caseTrend(
            String caseId,
            List<CasePoint> points,
            int sourceCount) {
        Set<String> regimes = new LinkedHashSet<>();
        List<String> changed = new ArrayList<>();
        CasePoint previous = null;
        for (CasePoint point : points) {
            regimes.add(point.snapshot().fixtureSetFingerprint() + ':'
                    + point.snapshot().planSetFingerprint());
            if (previous != null && previous.runRegime().equals(point.runRegime())
                    && !previous.snapshot().outcomeSetFingerprint()
                    .equals(point.snapshot().outcomeSetFingerprint())) {
                changed.add(point.runId());
            }
            previous = point;
        }
        TestSuiteStabilityTrendAnalysis.CaseTrendStatus status;
        if (points.size() != sourceCount || points.stream().anyMatch(value ->
                value.snapshot().status() == TestSuiteStabilityRun.CaseStatus.INCONCLUSIVE)) {
            status = TestSuiteStabilityTrendAnalysis.CaseTrendStatus.INCONCLUSIVE;
        } else if (points.stream().anyMatch(value ->
                value.snapshot().status() == TestSuiteStabilityRun.CaseStatus.FLAKY)
                || !changed.isEmpty()) {
            status = TestSuiteStabilityTrendAnalysis.CaseTrendStatus.INSTABILITY_OBSERVED;
        } else if (regimes.size() > 1) {
            status = TestSuiteStabilityTrendAnalysis.CaseTrendStatus.REGIME_DRIFT_OBSERVED;
        } else if (points.stream().anyMatch(value -> value.snapshot().status()
                == TestSuiteStabilityRun.CaseStatus.CONSISTENT_FAILURE)) {
            status = TestSuiteStabilityTrendAnalysis.CaseTrendStatus
                    .CONSISTENT_FAILURE_OBSERVED;
        } else {
            status = TestSuiteStabilityTrendAnalysis.CaseTrendStatus.STABLE_PASS;
        }
        return new TestSuiteStabilityTrendAnalysis.CaseTrend(caseId, status,
                points.stream().map(CasePoint::runId).toList(), changed, regimes.size());
    }

    private static List<TestSuiteStabilityTrendAnalysis.CorrelationSignal> signals(
            List<TestSuiteStabilityTrendAnalysis.SourceObservation> sources) {
        List<TestSuiteStabilityTrendAnalysis.CorrelationSignal> result = new ArrayList<>();
        for (TestSuiteStabilityTrendAnalysis.SourceObservation source : sources) {
            List<String> flaky = source.cases().stream()
                    .filter(value -> value.status() == TestSuiteStabilityRun.CaseStatus.FLAKY)
                    .map(TestSuiteStabilityTrendAnalysis.CaseSnapshot::caseId).sorted().toList();
            if (flaky.size() >= 2) {
                result.add(new TestSuiteStabilityTrendAnalysis.CorrelationSignal(
                        TestSuiteStabilityTrendAnalysis.CorrelationSignalType.MULTI_CASE_FLAKINESS,
                        "", source.stabilityRunId(), source.regimeFingerprint(), flaky));
            }
        }
        for (int index = 1; index < sources.size(); index++) {
            TestSuiteStabilityTrendAnalysis.SourceObservation previous = sources.get(index - 1);
            TestSuiteStabilityTrendAnalysis.SourceObservation current = sources.get(index);
            if (!previous.regimeFingerprint().equals(current.regimeFingerprint())) {
                continue;
            }
            Map<String, TestSuiteStabilityTrendAnalysis.CaseSnapshot> previousCases =
                    new LinkedHashMap<>();
            previous.cases().forEach(value -> previousCases.put(value.caseId(), value));
            List<String> shifted = current.cases().stream()
                    .filter(value -> previousCases.containsKey(value.caseId()))
                    .filter(value -> conclusive(value)
                            && conclusive(previousCases.get(value.caseId())))
                    .filter(value -> !value.outcomeSetFingerprint().equals(
                            previousCases.get(value.caseId()).outcomeSetFingerprint()))
                    .map(TestSuiteStabilityTrendAnalysis.CaseSnapshot::caseId).sorted().toList();
            if (shifted.size() >= 2) {
                result.add(new TestSuiteStabilityTrendAnalysis.CorrelationSignal(
                        TestSuiteStabilityTrendAnalysis.CorrelationSignalType
                                .COINCIDENT_OUTCOME_SHIFT,
                        previous.stabilityRunId(), current.stabilityRunId(),
                        current.regimeFingerprint(), shifted));
            }
        }
        return List.copyOf(result);
    }

    private static TestSuiteStabilityTrendAnalysis.Status status(
            List<TestSuiteStabilityTrendAnalysis.SourceObservation> sources,
            int minimumRuns,
            boolean complete,
            List<TestSuiteStabilityTrendAnalysis.CaseTrend> trends) {
        if (!complete || sources.size() < minimumRuns
                || sources.stream().anyMatch(value ->
                value.status() == TestSuiteStabilityRun.Status.INCONCLUSIVE)
                || trends.stream().anyMatch(value -> value.status()
                == TestSuiteStabilityTrendAnalysis.CaseTrendStatus.INCONCLUSIVE)) {
            return TestSuiteStabilityTrendAnalysis.Status.INCONCLUSIVE;
        }
        if (sources.stream().anyMatch(value -> value.status() == TestSuiteStabilityRun.Status.FLAKY)
                || trends.stream().anyMatch(value -> value.status()
                == TestSuiteStabilityTrendAnalysis.CaseTrendStatus.INSTABILITY_OBSERVED)) {
            return TestSuiteStabilityTrendAnalysis.Status.INSTABILITY_OBSERVED;
        }
        if (sources.stream().map(
                TestSuiteStabilityTrendAnalysis.SourceObservation::regimeFingerprint)
                .distinct().count() > 1
                || trends.stream().anyMatch(value -> value.status()
                == TestSuiteStabilityTrendAnalysis.CaseTrendStatus.REGIME_DRIFT_OBSERVED)) {
            return TestSuiteStabilityTrendAnalysis.Status.REGIME_DRIFT_OBSERVED;
        }
        if (sources.stream().anyMatch(value -> value.status()
                == TestSuiteStabilityRun.Status.CONSISTENT_FAILURE)
                || trends.stream().anyMatch(value -> value.status()
                == TestSuiteStabilityTrendAnalysis.CaseTrendStatus
                .CONSISTENT_FAILURE_OBSERVED)) {
            return TestSuiteStabilityTrendAnalysis.Status.CONSISTENT_FAILURE_OBSERVED;
        }
        return TestSuiteStabilityTrendAnalysis.Status.STABLE_PASS;
    }

    private static boolean conclusive(TestSuiteStabilityTrendAnalysis.CaseSnapshot value) {
        return value.status() != TestSuiteStabilityRun.CaseStatus.INCONCLUSIVE;
    }

    record Result(
            TestSuiteStabilityTrendAnalysis.Status status,
            List<TestSuiteStabilityTrendAnalysis.CaseTrend> caseTrends,
            List<TestSuiteStabilityTrendAnalysis.CorrelationSignal> signals,
            List<String> diagnostics) {
    }

    private record CasePoint(
            String runId,
            String runRegime,
            TestSuiteStabilityTrendAnalysis.CaseSnapshot snapshot) {
    }
}
