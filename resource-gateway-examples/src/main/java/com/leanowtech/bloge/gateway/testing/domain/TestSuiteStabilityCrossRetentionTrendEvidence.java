package com.leanowtech.bloge.gateway.testing.domain;

import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityCrossRetentionTrendAnalysisRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerRange;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence.CaseTrend;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence.CorrelationSignal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Signed payload-free trend derived from one exact compact-observation ledger range.
 *
 * <p>The evidence carries the complete producer-authoritative range rather than only derived
 * labels. An independent consumer can therefore verify every compact observation signature,
 * rebuild the entry chain and floor/head closure, and rederive all trend results.</p>
 *
 * @param schemaVersion exact evidence wire generation
 * @param trendAnalysisId deterministic request-and-range identity
 * @param requestFingerprint canonical request identity
 * @param suiteRef exact immutable suite revision
 * @param minimumRuns precommitted minimum observation count
 * @param maximumRuns precommitted range budget
 * @param observedRuns exact returned observation count
 * @param sourceOrder deterministic order used to derive adjacent-run signals
 * @param range complete fingerprinted floor/head/page snapshot
 * @param status deterministic aggregate trend state
 * @param caseTrends ordered per-case trend states
 * @param correlationSignals bounded non-causal co-variation signals
 * @param causalityStatus fixed disclosure that no common cause was proven
 * @param diagnostics bounded machine-readable reasons
 * @param evaluatedAt producer database observation time
 */
public record TestSuiteStabilityCrossRetentionTrendEvidence(
        String schemaVersion,
        String trendAnalysisId,
        String requestFingerprint,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        int minimumRuns,
        int maximumRuns,
        int observedRuns,
        SourceOrder sourceOrder,
        TestSuiteStabilityObservationLedgerRange range,
        TestSuiteStabilityTrendEvidence.Status status,
        List<CaseTrend> caseTrends,
        List<CorrelationSignal> correlationSignals,
        TestSuiteStabilityTrendEvidence.CausalityStatus causalityStatus,
        List<String> diagnostics,
        Instant evaluatedAt
) {
    /** Current signed cross-retention trend evidence generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityCrossRetentionTrendEvidence.v1";
    private static final Pattern TREND_ID =
            Pattern.compile("stability-cross-retention-trend-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");

    /** Freezes all derived closures and enforces range/result consistency. */
    public TestSuiteStabilityCrossRetentionTrendEvidence {
        schemaVersion = normalized(schemaVersion);
        trendAnalysisId = normalized(trendAnalysisId);
        requestFingerprint = normalized(requestFingerprint);
        caseTrends = caseTrends == null ? List.of() : List.copyOf(caseTrends);
        correlationSignals = correlationSignals == null
                ? List.of() : List.copyOf(correlationSignals);
        diagnostics = sorted(diagnostics);
        boolean countsValid = minimumRuns
                >= TestSuiteStabilityCrossRetentionTrendAnalysisRequest.MINIMUM_RUNS
                && maximumRuns >= minimumRuns
                && maximumRuns
                <= TestSuiteStabilityCrossRetentionTrendAnalysisRequest.MAXIMUM_RUNS
                && observedRuns >= 0 && observedRuns <= maximumRuns
                && range != null && observedRuns == range.entries().size();
        boolean resultValid = status != null
                && sourceOrder == SourceOrder.SOURCE_CREATED_AT_THEN_STABILITY_RUN_ID
                && causalityStatus == TestSuiteStabilityTrendEvidence.CausalityStatus.NOT_PROVEN
                && diagnostics.stream().allMatch(value -> REASON.matcher(value).matches())
                && caseTrends.stream().map(CaseTrend::caseId).distinct().count()
                == caseTrends.size()
                && (status == TestSuiteStabilityTrendEvidence.Status.INCONCLUSIVE
                || observedRuns >= minimumRuns)
                && (observedRuns > 0
                || (caseTrends.isEmpty() && correlationSignals.isEmpty()));
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !TREND_ID.matcher(trendAnalysisId).matches()
                || !FINGERPRINT.matcher(requestFingerprint).matches()
                || suiteRef == null || !countsValid || !suiteRef.equals(range.suiteRef())
                || !resultValid || evaluatedAt == null
                || !evaluatedAt.equals(range.observedAt())) {
            throw new IllegalArgumentException(
                    "Complete cross-retention stability trend evidence is required");
        }
    }

    /** Deterministic ordering contract used before trend projection. */
    public enum SourceOrder {
        /** Sort by signed source creation time and then stable run identity. */
        SOURCE_CREATED_AT_THEN_STABILITY_RUN_ID
    }

    private static List<String> sorted(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>(new LinkedHashSet<>(values));
        result.replaceAll(TestSuiteStabilityCrossRetentionTrendEvidence::normalized);
        result.removeIf(String::isBlank);
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
