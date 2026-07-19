package com.leanowtech.bloge.gateway.testing.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Payload-free longitudinal projection over one exact retained suite-stability window.
 *
 * <p>The evidence reports deterministic transitions and bounded correlation signals. It never
 * confirms causality, predicts future behavior, or authorizes quarantine or publication.</p>
 *
 * @param schemaVersion exact evidence protocol version
 * @param trendAnalysisId deterministic request-and-source identity
 * @param requestFingerprint canonical request fingerprint
 * @param suiteRef exact immutable suite revision
 * @param fromInclusive inclusive persistence-time lower boundary
 * @param toExclusive exclusive persistence-time upper boundary
 * @param minimumRuns precommitted minimum source count
 * @param maximumRuns precommitted source budget
 * @param observedRuns retained source count
 * @param expiredMatchingRuns matching rows unavailable under evidence retention
 * @param completeWindow whether no retention or query-budget gap exists
 * @param status deterministic aggregate longitudinal status
 * @param sources ordered source summaries
 * @param caseTrends ordered per-case longitudinal summaries
 * @param correlationSignals bounded non-causal co-variation signals
 * @param causalityStatus fixed non-causal disclosure
 * @param diagnostics bounded machine-readable incompleteness reasons
 * @param evaluatedAt database observation time
 */
public record TestSuiteStabilityTrendEvidence(
        String schemaVersion,
        String trendAnalysisId,
        String requestFingerprint,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        Instant fromInclusive,
        Instant toExclusive,
        int minimumRuns,
        int maximumRuns,
        int observedRuns,
        int expiredMatchingRuns,
        boolean completeWindow,
        Status status,
        List<RunObservation> sources,
        List<CaseTrend> caseTrends,
        List<CorrelationSignal> correlationSignals,
        CausalityStatus causalityStatus,
        List<String> diagnostics,
        Instant evaluatedAt
) {
    /** Current retained-window evidence generation. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteStabilityTrendEvidence.v1";
    private static final Pattern TREND_ID = Pattern.compile("stability-trend-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");

    /** Closed aggregate states without a forecasting claim. */
    public enum Status {
        STABLE_PASS,
        CONSISTENT_FAILURE_OBSERVED,
        INSTABILITY_OBSERVED,
        REGIME_DRIFT_OBSERVED,
        INCONCLUSIVE
    }

    /** Closed per-case states derived from source summaries. */
    public enum CaseTrendStatus {
        STABLE_PASS,
        CONSISTENT_FAILURE_OBSERVED,
        INSTABILITY_OBSERVED,
        REGIME_DRIFT_OBSERVED,
        INCONCLUSIVE
    }

    /** Signal vocabulary intentionally excludes a common-cause conclusion. */
    public enum CorrelationSignalType {
        MULTI_CASE_FLAKINESS,
        COINCIDENT_OUTCOME_SHIFT
    }

    /** V1 can surface candidates but can never prove causality. */
    public enum CausalityStatus {
        NOT_PROVEN
    }

    /**
     * One independently reconstructable source analysis summary.
     *
     * @param stabilityRunId source stability identity
     * @param evidenceFingerprint source evidence identity
     * @param attestationFingerprint exact source attestation identity
     * @param evidenceSchemaVersion source evidence generation
     * @param targetFingerprint exact target dependency identity
     * @param status source stability status
     * @param promotionStatus source promotion verdict
     * @param quarantineStatus source quarantine recommendation
     * @param statisticalStatus optional source statistical conclusion
     * @param regimeFingerprint derived fixture/plan regime identity
     * @param cases ordered case summaries
     * @param startedAt source execution start
     * @param completedAt source execution completion
     * @param createdAt terminal persistence time
     */
    public record RunObservation(
            String stabilityRunId,
            String evidenceFingerprint,
            String attestationFingerprint,
            String evidenceSchemaVersion,
            String targetFingerprint,
            TestSuiteStabilityEvidence.Status status,
            TestSuiteStabilityEvidence.PromotionStatus promotionStatus,
            TestSuiteStabilityEvidence.QuarantineStatus quarantineStatus,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            TestSuiteStabilityEvidence.StatisticalStatus statisticalStatus,
            String regimeFingerprint,
            List<CaseSnapshot> cases,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt
    ) {
        /** Validates a complete payload-free source projection. */
        public RunObservation {
            stabilityRunId = normalized(stabilityRunId);
            evidenceFingerprint = normalized(evidenceFingerprint);
            attestationFingerprint = normalized(attestationFingerprint);
            evidenceSchemaVersion = normalized(evidenceSchemaVersion);
            targetFingerprint = normalized(targetFingerprint);
            regimeFingerprint = normalized(regimeFingerprint);
            cases = cases == null ? List.of() : List.copyOf(cases);
            if (!stabilityRunId.matches("stability-[a-f0-9]{64}")
                    || !fingerprint(evidenceFingerprint) || !fingerprint(attestationFingerprint)
                    || evidenceSchemaVersion.isBlank() || !fingerprint(targetFingerprint)
                    || status == null || promotionStatus == null || quarantineStatus == null
                    || !fingerprint(regimeFingerprint) || cases.isEmpty()
                    || duplicateCase(cases) || startedAt == null || completedAt == null
                    || createdAt == null || completedAt.isBefore(startedAt)
                    || createdAt.isBefore(completedAt)) {
                throw new IllegalArgumentException("Complete stability trend source is required");
            }
        }
    }

    /**
     * Minimal case material needed to reconstruct regimes and outcome transitions.
     *
     * @param caseId exact suite-local case identity
     * @param status source case stability state
     * @param outcomeSetFingerprint sorted verified outcome-identity set fingerprint
     * @param fixtureSetFingerprint sorted verified fixture-identity set fingerprint
     * @param planSetFingerprint sorted verified effective-plan set fingerprint
     */
    public record CaseSnapshot(
            String caseId,
            TestSuiteStabilityEvidence.CaseStatus status,
            String outcomeSetFingerprint,
            String fixtureSetFingerprint,
            String planSetFingerprint
    ) {
        /** Validates one payload-free case summary. */
        public CaseSnapshot {
            caseId = normalized(caseId);
            outcomeSetFingerprint = normalized(outcomeSetFingerprint);
            fixtureSetFingerprint = normalized(fixtureSetFingerprint);
            planSetFingerprint = normalized(planSetFingerprint);
            if (caseId.isBlank() || status == null || !fingerprint(outcomeSetFingerprint)
                    || !fingerprint(fixtureSetFingerprint) || !fingerprint(planSetFingerprint)) {
                throw new IllegalArgumentException("Complete stability trend case snapshot required");
            }
        }
    }

    /**
     * Deterministic longitudinal result for one exact case.
     *
     * @param caseId exact case identity
     * @param status derived longitudinal state
     * @param sourceRunIds ordered contributing sources
     * @param changedAtRunIds sources where a same-regime outcome changed
     * @param regimeCount number of distinct case fixture/plan regimes
     */
    public record CaseTrend(
            String caseId,
            CaseTrendStatus status,
            List<String> sourceRunIds,
            List<String> changedAtRunIds,
            int regimeCount
    ) {
        /** Freezes ordered source closure and validates bounded counters. */
        public CaseTrend {
            caseId = normalized(caseId);
            sourceRunIds = sourceRunIds == null ? List.of() : List.copyOf(sourceRunIds);
            changedAtRunIds = changedAtRunIds == null ? List.of() : List.copyOf(changedAtRunIds);
            if (caseId.isBlank() || status == null || sourceRunIds.isEmpty()
                    || regimeCount < 1 || !sourceRunIds.containsAll(changedAtRunIds)) {
                throw new IllegalArgumentException("Complete derived case trend is required");
            }
        }
    }

    /**
     * Bounded correlation signal that deliberately carries no causal label.
     *
     * @param type exact signal type
     * @param previousRunId previous source for an adjacent shift; blank for within-run flakiness
     * @param currentRunId source where the signal was observed
     * @param regimeFingerprint exact comparable regime
     * @param caseIds sorted affected case identities
     */
    public record CorrelationSignal(
            CorrelationSignalType type,
            String previousRunId,
            String currentRunId,
            String regimeFingerprint,
            List<String> caseIds
    ) {
        /** Normalizes and validates one non-causal signal. */
        public CorrelationSignal {
            previousRunId = normalized(previousRunId);
            currentRunId = normalized(currentRunId);
            regimeFingerprint = normalized(regimeFingerprint);
            caseIds = sorted(caseIds);
            boolean shift = type == CorrelationSignalType.COINCIDENT_OUTCOME_SHIFT;
            if (type == null || !currentRunId.matches("stability-[a-f0-9]{64}")
                    || (shift && !previousRunId.matches("stability-[a-f0-9]{64}"))
                    || (!shift && !previousRunId.isBlank()) || !fingerprint(regimeFingerprint)
                    || caseIds.size() < 2) {
                throw new IllegalArgumentException("Complete bounded correlation signal required");
            }
        }
    }

    /** Freezes all ordered closures and validates cross-field completeness invariants. */
    public TestSuiteStabilityTrendEvidence {
        schemaVersion = normalized(schemaVersion);
        trendAnalysisId = normalized(trendAnalysisId);
        requestFingerprint = normalized(requestFingerprint);
        sources = sources == null ? List.of() : List.copyOf(sources);
        caseTrends = caseTrends == null ? List.of() : List.copyOf(caseTrends);
        correlationSignals = correlationSignals == null ? List.of() : List.copyOf(correlationSignals);
        diagnostics = sorted(diagnostics);
        boolean countsValid = minimumRuns >= 2 && maximumRuns >= minimumRuns
                && maximumRuns <= 100 && observedRuns == sources.size()
                && observedRuns <= maximumRuns && expiredMatchingRuns >= 0;
        boolean completeValid = completeWindow == (expiredMatchingRuns == 0
                && !diagnostics.contains("SOURCE_WINDOW_TRUNCATED"));
        if (!SCHEMA_VERSION.equals(schemaVersion) || !TREND_ID.matcher(trendAnalysisId).matches()
                || !fingerprint(requestFingerprint) || suiteRef == null
                || fromInclusive == null || toExclusive == null
                || !fromInclusive.isBefore(toExclusive) || !countsValid || !completeValid
                || status == null || causalityStatus != CausalityStatus.NOT_PROVEN
                || evaluatedAt == null || duplicateSource(sources) || !chronological(sources)
                || duplicateTrend(caseTrends)
                || diagnostics.stream().anyMatch(value -> !REASON.matcher(value).matches())
                || (status != Status.INCONCLUSIVE
                && (!completeWindow || observedRuns < minimumRuns))) {
            throw new IllegalArgumentException("Complete derived stability trend evidence required");
        }
    }

    private static boolean duplicateSource(List<RunObservation> values) {
        return values.stream().map(RunObservation::stabilityRunId).distinct().count()
                != values.size();
    }

    private static boolean chronological(List<RunObservation> values) {
        Comparator<RunObservation> order = Comparator.comparing(RunObservation::createdAt)
                .thenComparing(RunObservation::stabilityRunId);
        for (int index = 1; index < values.size(); index++) {
            if (order.compare(values.get(index - 1), values.get(index)) > 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean duplicateCase(List<CaseSnapshot> values) {
        return values.stream().map(CaseSnapshot::caseId).distinct().count() != values.size();
    }

    private static boolean duplicateTrend(List<CaseTrend> values) {
        return values.stream().map(CaseTrend::caseId).distinct().count() != values.size();
    }

    private static boolean fingerprint(String value) {
        return FINGERPRINT.matcher(normalized(value)).matches();
    }

    private static List<String> sorted(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>(new LinkedHashSet<>(values));
        result.replaceAll(TestSuiteStabilityTrendEvidence::normalized);
        result.removeIf(String::isBlank);
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
