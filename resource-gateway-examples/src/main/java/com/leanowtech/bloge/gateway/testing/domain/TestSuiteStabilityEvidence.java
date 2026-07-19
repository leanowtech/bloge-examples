package com.leanowtech.bloge.gateway.testing.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Payload-free evidence derived from bounded reruns of one exact immutable test suite.
 *
 * <p>Stability is stricter than repeated pass status. Every case must retain the same verified
 * semantic-result fingerprint under the same target, fixture, and effective plan. A differing
 * verified outcome proves flakiness; missing or drifted evidence remains inconclusive. Quarantine
 * is only a promotion-blocking recommendation and can never convert a failure into a pass.</p>
 *
 * @param schemaVersion exact stability-evidence protocol version
 * @param stabilityRunId deterministic identity derived from scope and request intent
 * @param clientRequestId caller-owned idempotency key for the bounded rerun
 * @param suiteRef exact immutable suite revision
 * @param target exact suite target
 * @param requestedAttempts requested bounded rerun count
 * @param status server-derived aggregate stability status
 * @param attempts ordered source suite-run closure
 * @param caseResults ordered case stability closure
 * @param promotion server-derived promotion gate
 * @param quarantine server-derived quarantine recommendation
 * @param statisticalAssessment exact v3-v5 probability-model assessment; absent in v1/v2
 * @param startedAt earliest source suite-run start
 * @param completedAt latest source suite-run completion
 * @param diagnostics bounded payload-free diagnostic codes
 * @param metadata bounded caller provenance
 */
public record TestSuiteStabilityEvidence(
        String schemaVersion,
        String stabilityRunId,
        String clientRequestId,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        TestSuite.Target target,
        int requestedAttempts,
        Status status,
        List<AttemptResult> attempts,
        List<CaseStabilityResult> caseResults,
        PromotionVerdict promotion,
        QuarantineVerdict quarantine,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        StatisticalAssessment statisticalAssessment,
        Instant startedAt,
        Instant completedAt,
        List<String> diagnostics,
        Map<String, Object> metadata
) {
    /** Historical stability evidence version without source-promotion closure. */
    public static final String SCHEMA_VERSION_V1 = "bloge.testSuiteStabilityEvidence.v1";
    /** Deterministic evidence version with source-promotion closure. */
    public static final String SCHEMA_VERSION_V2 = "bloge.testSuiteStabilityEvidence.v2";
    /** Legacy zero-event statistical evidence version. */
    public static final String SCHEMA_VERSION_V3 = "bloge.testSuiteStabilityEvidence.v3";
    /** Current wire-supported baseline-conditional fixed-horizon exact-rate evidence version. */
    public static final String SCHEMA_VERSION_V4 = "bloge.testSuiteStabilityEvidence.v4";
    /** Staged anytime-valid sequential evidence version. */
    public static final String SCHEMA_VERSION_V5 = "bloge.testSuiteStabilityEvidence.v5";
    /** Current fully served evidence protocol version. */
    public static final String SCHEMA_VERSION = SCHEMA_VERSION_V4;
    /** Minimum reruns required before stability may be claimed. */
    public static final int MIN_ATTEMPTS = 3;
    /** Generation-one upper bound preventing accidental CI amplification. */
    public static final int MAX_ATTEMPTS = 20;
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern STABILITY_RUN_ID = Pattern.compile("stability-[a-f0-9]{64}");
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");
    private static final Pattern METADATA_KEY = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_.-]{0,127}");
    private static final int MAX_METADATA_PROPERTIES = 32;
    private static final int MAX_METADATA_STRING_LENGTH = 512;
    /** Fixed conditional assumptions disclosed by every legacy v3 probability claim. */
    public static final List<String> STATISTICAL_MODEL_ASSUMPTIONS = List.of(
            "ATTEMPTS_EXCHANGEABLE_WITHIN_ANALYSIS_WINDOW",
            "EXECUTION_REGIME_STATIONARY_WITHIN_ANALYSIS_WINDOW",
            "NO_UNOBSERVED_COMMON_CAUSE_CLAIM",
            "OUTCOME_EVENT_DETECTION_BY_SEMANTIC_FINGERPRINT");
    /** Fixed disclosures for the baseline-conditional v4 exact-rate claim. */
    public static final List<String> BASELINE_CONDITIONAL_MODEL_ASSUMPTIONS = List.of(
            "ATTEMPTS_EXCHANGEABLE_WITHIN_ANALYSIS_WINDOW",
            "EXECUTION_REGIME_STATIONARY_WITHIN_ANALYSIS_WINDOW",
            "NO_UNOBSERVED_COMMON_CAUSE_CLAIM",
            "OUTCOME_EVENT_DETECTION_BY_SEMANTIC_FINGERPRINT",
            "BASELINE_IS_FIRST_VERIFIED_ATTEMPT",
            "RATE_IS_CONDITIONAL_ON_OBSERVED_BASELINE");
    /** Fixed disclosures for the v5 baseline-conditional anytime-valid claim. */
    public static final List<String> ANYTIME_VALID_MODEL_ASSUMPTIONS = List.of(
            "CONDITIONAL_EVENT_PROBABILITY_NULL_BOUND",
            "OUTCOME_EVENT_DETECTION_BY_SEMANTIC_FINGERPRINT",
            "BASELINE_IS_FIRST_VERIFIED_ATTEMPT",
            "RATE_IS_CONDITIONAL_ON_OBSERVED_BASELINE",
            "ALTERNATIVE_RATE_PRECOMMITTED_BEFORE_EXECUTION",
            "VILLE_ANYTIME_ERROR_CONTROL",
            "NO_UNOBSERVED_COMMON_CAUSE_CLAIM");

    /** Aggregate stability outcome. */
    public enum Status {
        /** Every case passed with one invariant verified semantic outcome. */
        STABLE,
        /** At least one case produced two different verified outcomes. */
        FLAKY,
        /** Every observation was complete, but at least one case failed consistently. */
        CONSISTENT_FAILURE,
        /** Available evidence cannot prove stability, flakiness, or consistent failure. */
        INCONCLUSIVE
    }

    /** Trust state for one source suite-run attempt. */
    public enum AttemptStatus {
        /** Terminal suite attestation and every child observation were verified. */
        VERIFIED,
        /** The source attempt or one of its children could not be proven. */
        INCONCLUSIVE
    }

    /** Stability classification for one exact suite case. */
    public enum CaseStatus {
        /** All requested observations are verified, passing, and semantically identical. */
        STABLE_PASS,
        /** All requested observations are verified, failing, and semantically identical. */
        CONSISTENT_FAILURE,
        /** At least two verified observations have different semantic outcomes. */
        FLAKY,
        /** Fewer than two variants were proven and at least one observation is incomplete. */
        INCONCLUSIVE
    }

    /** Trust state for one case observation. */
    public enum ObservationStatus {
        VERIFIED,
        INCONCLUSIVE
    }

    /** Promotion decision derived from stability evidence. */
    public enum PromotionStatus {
        ELIGIBLE,
        BLOCKED
    }

    /** Quarantine recommendation; it never changes business correctness. */
    public enum QuarantineStatus {
        NOT_REQUIRED,
        REQUIRED,
        UNDETERMINED
    }

    /** Derived statistical conclusion for a precommitted fixed or anytime-valid horizon. */
    public enum StatisticalStatus {
        /** The complete non-censored sample admitted the configured exact rate ceiling. */
        SATISFIED,
        /** The complete non-censored sample did not admit the configured rate ceiling. */
        REJECTED,
        /** Missing or invalid evidence prevents the probability claim. */
        INCONCLUSIVE
    }

    /** Terminal reason for ending one statistical sampling generation. */
    public enum StatisticalStopReason {
        /** Every attempt in the caller-precommitted fixed horizon was executed. */
        FIXED_HORIZON_REACHED,
        /** The anytime-valid e-process crossed its threshold before the maximum horizon. */
        E_VALUE_THRESHOLD_REACHED,
        /** The anytime-valid process exhausted its maximum horizon without crossing. */
        MAXIMUM_HORIZON_REACHED,
        /** Incomplete evidence made the sequential claim terminally inconclusive. */
        CENSORING_OBSERVED
    }

    /**
     * One source suite-run attempt.
     *
     * @param attempt one-based rerun coordinate
     * @param status source trust status
     * @param suiteRunId source suite-run id
     * @param aggregateEvidenceFingerprint signed aggregate evidence fingerprint
     * @param suiteStatus source suite aggregate status
     * @param sourcePromotionStatus source suite release-promotion status
     * @param sourcePromotionReasons exact bounded source promotion reasons
     * @param startedAt source start time
     * @param completedAt source terminal time
     * @param diagnosticCode bounded reason when the source is inconclusive
     */
    public record AttemptResult(
            int attempt,
            AttemptStatus status,
            String suiteRunId,
            String aggregateEvidenceFingerprint,
            TestSuiteRunEvidence.Status suiteStatus,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            TestSuiteRunEvidence.PromotionStatus sourcePromotionStatus,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            List<String> sourcePromotionReasons,
            Instant startedAt,
            Instant completedAt,
            String diagnosticCode
    ) {
        /** Normalizes one payload-free source reference. */
        public AttemptResult {
            status = status == null ? AttemptStatus.INCONCLUSIVE : status;
            suiteRunId = normalized(suiteRunId);
            aggregateEvidenceFingerprint = normalized(aggregateEvidenceFingerprint);
            sourcePromotionReasons = sortedStrings(sourcePromotionReasons);
            diagnosticCode = normalized(diagnosticCode);
            if (attempt < 1) {
                throw new IllegalArgumentException("Stability attempt must be one-based");
            }
            if (sourcePromotionReasons.size() > 20 || sourcePromotionReasons.stream()
                    .anyMatch(value -> !REASON_CODE.matcher(value).matches())) {
                throw new IllegalArgumentException(
                        "Source promotion reasons must be bounded machine codes");
            }
            if (sourcePromotionStatus == TestSuiteRunEvidence.PromotionStatus.NOT_EVALUATED) {
                throw new IllegalArgumentException(
                        "Terminal source promotion cannot remain unevaluated");
            }
            if (status == AttemptStatus.VERIFIED
                    && (suiteRunId.isBlank() || !fingerprint(aggregateEvidenceFingerprint)
                    || suiteStatus == null || suiteStatus == TestSuiteRunEvidence.Status.RUNNING
                    || (sourcePromotionStatus == TestSuiteRunEvidence.PromotionStatus.ELIGIBLE
                    && !sourcePromotionReasons.isEmpty())
                    || (sourcePromotionStatus == TestSuiteRunEvidence.PromotionStatus.BLOCKED
                    && sourcePromotionReasons.isEmpty())
                    || startedAt == null || completedAt == null
                    || completedAt.isBefore(startedAt) || !diagnosticCode.isBlank())) {
                throw new IllegalArgumentException(
                        "Verified stability attempt requires complete terminal source evidence");
            }
            if (sourcePromotionStatus == null && !sourcePromotionReasons.isEmpty()) {
                throw new IllegalArgumentException(
                        "Source promotion reasons require a source promotion status");
            }
        }
    }

    /**
     * One payload-free child observation.
     *
     * @param attempt one-based rerun coordinate
     * @param status observation trust status
     * @param runId child test-run id
     * @param evidenceFingerprint complete child evidence fingerprint
     * @param evidenceStatus child business/test outcome
     * @param evidenceClass child evidence trust class
     * @param fixtureBundleFingerprint exact fixture identity
     * @param planFingerprint exact effective execution-plan identity
     * @param semanticResultFingerprint canonical business-result identity
     * @param diagnosticCode bounded reason when the observation is inconclusive
     */
    public record CaseObservation(
            int attempt,
            ObservationStatus status,
            String runId,
            String evidenceFingerprint,
            TestRunEvidence.Status evidenceStatus,
            TestRunEvidence.EvidenceClass evidenceClass,
            String fixtureBundleFingerprint,
            String planFingerprint,
            String semanticResultFingerprint,
            String diagnosticCode
    ) {
        /** Normalizes and validates one child evidence reference. */
        public CaseObservation {
            status = status == null ? ObservationStatus.INCONCLUSIVE : status;
            runId = normalized(runId);
            evidenceFingerprint = normalized(evidenceFingerprint);
            fixtureBundleFingerprint = normalized(fixtureBundleFingerprint);
            planFingerprint = normalized(planFingerprint);
            semanticResultFingerprint = normalized(semanticResultFingerprint);
            diagnosticCode = normalized(diagnosticCode);
            if (attempt < 1) {
                throw new IllegalArgumentException("Case observation attempt must be one-based");
            }
            if (status == ObservationStatus.VERIFIED
                    && (runId.isBlank() || !fingerprint(evidenceFingerprint)
                    || evidenceStatus == null || evidenceClass == null
                    || !fingerprint(fixtureBundleFingerprint) || !fingerprint(planFingerprint)
                    || !fingerprint(semanticResultFingerprint) || !diagnosticCode.isBlank())) {
                throw new IllegalArgumentException(
                        "Verified stability observation requires complete semantic evidence");
            }
        }

        /**
         * Returns the complete outcome identity compared across equivalent attempts.
         *
         * @return status plus semantic fingerprint, or blank for incomplete evidence
         */
        public String outcomeIdentity() {
            return status == ObservationStatus.VERIFIED
                    ? evidenceStatus.name() + ':' + semanticResultFingerprint : "";
        }
    }

    /**
     * Stability result for one suite case.
     *
     * @param caseId suite-local stable case id
     * @param caseType declared case intent
     * @param fixtureBundleRef exact governed fixture dependency
     * @param status derived case stability status
     * @param observations one observation per requested attempt
     * @param distinctVerifiedOutcomes number of distinct verified outcome identities
     * @param diagnosticCodes sorted bounded diagnostics
     */
    public record CaseStabilityResult(
            String caseId,
            TestSuite.CaseType caseType,
            TestSuite.FixtureBundleRef fixtureBundleRef,
            CaseStatus status,
            List<CaseObservation> observations,
            int distinctVerifiedOutcomes,
            List<String> diagnosticCodes
    ) {
        /** Re-derives classification and rejects caller-supplied aggregate lies. */
        public CaseStabilityResult {
            caseId = normalized(caseId);
            status = status == null ? CaseStatus.INCONCLUSIVE : status;
            observations = observations == null ? List.of() : List.copyOf(observations);
            diagnosticCodes = sortedStrings(diagnosticCodes);
            Set<String> outcomes = new LinkedHashSet<>();
            observations.stream()
                    .filter(value -> value.status() == ObservationStatus.VERIFIED)
                    .map(CaseObservation::outcomeIdentity)
                    .forEach(outcomes::add);
            if (caseId.isBlank() || caseType == null || fixtureBundleRef == null
                    || distinctVerifiedOutcomes != outcomes.size()
                    || status != deriveCaseStatus(observations, outcomes)) {
                throw new IllegalArgumentException(
                        "Case stability status and outcome count must be server-derived");
            }
        }
    }

    /**
     * Exact probability-model assessment over the complete suite-attempt horizon.
     *
     * @param policy precommitted model coordinates copied from request v2 or v3
     * @param requiredAttempts exact minimum horizon derived from the policy
     * @param observedAttempts complete fixed horizon or terminal sequential prefix size
     * @param verifiedAttempts attempts with a verified source and child closure
     * @param censoredAttempts attempts excluded from inference but retained in the denominator
     * @param observedInstabilityEvents verified attempt vectors differing from the first vector
     * @param achievedConfidenceBps conservative threshold-confidence floor; zero when inconclusive
     * @param comparisonAttempts v4 post-baseline Bernoulli trial count; absent in v3
     * @param upperInstabilityRateBps v4 conservative one-sided exact rate bound; absent when
     *                                censored and in v3
     * @param firstBoundaryCrossingAttempt v5 first execution count whose e-value crossed;
     *                                     absent in v3/v4 and non-crossing v5 evidence
     * @param status server-derived statistical conclusion
     * @param stopReason server-derived fixed-horizon stop reason
     * @param assumptions exact conditional model assumptions; these are disclosures, not proofs
     */
    public record StatisticalAssessment(
            TestSuiteStabilityStatisticalPolicy policy,
            int requiredAttempts,
            int observedAttempts,
            int verifiedAttempts,
            int censoredAttempts,
            int observedInstabilityEvents,
            int achievedConfidenceBps,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            Integer comparisonAttempts,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            Integer upperInstabilityRateBps,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            Integer firstBoundaryCrossingAttempt,
            StatisticalStatus status,
            StatisticalStopReason stopReason,
            List<String> assumptions
    ) {
        /**
         * Backward-compatible v3 constructor without baseline-conditional rate coordinates.
         *
         * @param policy legacy zero-event policy
         * @param requiredAttempts minimum horizon derived from the policy
         * @param observedAttempts complete requested horizon size
         * @param verifiedAttempts attempts with verified source and child closure
         * @param censoredAttempts attempts without complete verified closure
         * @param observedInstabilityEvents vectors differing from the first verified vector
         * @param achievedConfidenceBps conservative legacy confidence floor
         * @param status legacy statistical conclusion
         * @param stopReason fixed-horizon stop reason
         * @param assumptions legacy v3 model disclosures
         */
        public StatisticalAssessment(
                TestSuiteStabilityStatisticalPolicy policy,
                int requiredAttempts,
                int observedAttempts,
                int verifiedAttempts,
                int censoredAttempts,
                int observedInstabilityEvents,
                int achievedConfidenceBps,
                StatisticalStatus status,
                StatisticalStopReason stopReason,
                List<String> assumptions) {
            this(policy, requiredAttempts, observedAttempts, verifiedAttempts, censoredAttempts,
                    observedInstabilityEvents, achievedConfidenceBps, null, null, null, status,
                    stopReason, assumptions);
        }

        /** Freezes the model disclosure and validates generation-specific bounded counters. */
        public StatisticalAssessment {
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            TestSuiteStabilityStatisticalPolicy.Model model = policy == null
                    ? null : policy.model();
            boolean legacy = model
                    == TestSuiteStabilityStatisticalPolicy.Model.ZERO_INSTABILITY_EXACT_BINOMIAL;
            boolean fixedRate = model == TestSuiteStabilityStatisticalPolicy.Model
                    .BASELINE_CONDITIONAL_EXACT_BINOMIAL;
            boolean anytime = model == TestSuiteStabilityStatisticalPolicy.Model
                    .BASELINE_CONDITIONAL_ANYTIME_VALID_E_PROCESS;
            boolean commonComparisonCoordinates = comparisonAttempts != null
                    && comparisonAttempts == Math.max(0, verifiedAttempts - 1)
                    && observedInstabilityEvents <= comparisonAttempts;
            boolean generationCoordinatesValid = legacy
                    ? comparisonAttempts == null && upperInstabilityRateBps == null
                    && firstBoundaryCrossingAttempt == null
                    && stopReason == StatisticalStopReason.FIXED_HORIZON_REACHED
                    && STATISTICAL_MODEL_ASSUMPTIONS.equals(assumptions)
                    : fixedRate
                    ? commonComparisonCoordinates
                    && firstBoundaryCrossingAttempt == null
                    && stopReason == StatisticalStopReason.FIXED_HORIZON_REACHED
                    && (censoredAttempts == 0
                    ? upperInstabilityRateBps != null
                    && upperInstabilityRateBps >= 0 && upperInstabilityRateBps <= 10_000
                    : upperInstabilityRateBps == null)
                    && BASELINE_CONDITIONAL_MODEL_ASSUMPTIONS.equals(assumptions)
                    : anytime && commonComparisonCoordinates
                    && upperInstabilityRateBps == null
                    && ANYTIME_VALID_MODEL_ASSUMPTIONS.equals(assumptions)
                    && validAnytimeTerminal(status, stopReason, observedAttempts,
                    censoredAttempts, achievedConfidenceBps,
                    firstBoundaryCrossingAttempt, policy.confidenceLevelBps());
            int minimumObservedAttempts = anytime ? 1
                    : TestSuiteStabilityStatisticalPolicy.MIN_ATTEMPTS;
            if (policy == null || requiredAttempts < TestSuiteStabilityStatisticalPolicy.MIN_ATTEMPTS
                    || requiredAttempts > TestSuiteStabilityStatisticalPolicy.MAX_ATTEMPTS
                    || observedAttempts < minimumObservedAttempts
                    || observedAttempts > TestSuiteStabilityStatisticalPolicy.MAX_ATTEMPTS
                    || verifiedAttempts < 0 || censoredAttempts < 0
                    || verifiedAttempts + censoredAttempts != observedAttempts
                    || observedInstabilityEvents < 0
                    || observedInstabilityEvents > Math.max(0, verifiedAttempts - 1)
                    || achievedConfidenceBps < 0 || achievedConfidenceBps > 10_000
                    || status == null || stopReason == null
                    || !generationCoordinatesValid) {
                throw new IllegalArgumentException(
                        "Complete server-derived statistical stability assessment is required");
            }
        }

        private static boolean validAnytimeTerminal(
                StatisticalStatus status,
                StatisticalStopReason stopReason,
                int observedAttempts,
                int censoredAttempts,
                int achievedConfidenceBps,
                Integer firstBoundaryCrossingAttempt,
                int confidenceLevelBps) {
            if (censoredAttempts > 0) {
                return status == StatisticalStatus.INCONCLUSIVE
                        && stopReason == StatisticalStopReason.CENSORING_OBSERVED
                        && firstBoundaryCrossingAttempt == null
                        && achievedConfidenceBps == 0;
            }
            if (status == StatisticalStatus.SATISFIED) {
                return stopReason == StatisticalStopReason.E_VALUE_THRESHOLD_REACHED
                        && firstBoundaryCrossingAttempt != null
                        && firstBoundaryCrossingAttempt == observedAttempts
                        && achievedConfidenceBps >= confidenceLevelBps;
            }
            return status == StatisticalStatus.REJECTED
                    && stopReason == StatisticalStopReason.MAXIMUM_HORIZON_REACHED
                    && firstBoundaryCrossingAttempt == null
                    && achievedConfidenceBps < confidenceLevelBps;
        }
    }

    /**
     * Stability promotion gate.
     *
     * @param status eligibility status
     * @param reasons stable fail-closed reason codes
     * @param stableCases stable passing cases
     * @param flakyCases proven flaky cases
     * @param consistentFailureCases consistently failing cases
     * @param inconclusiveCases cases without complete proof
     * @param allAttemptsVerified whether every source attempt and child closure was verified
     * @param allSourceSuitesPromotionEligible whether every verified source suite was releasable;
     *                                         null only for historical v1 evidence
     * @param statisticalConfidenceSatisfied whether v3-v5 statistical admission passed;
     *                                       null in v1/v2
     */
    public record PromotionVerdict(
            PromotionStatus status,
            List<String> reasons,
            int stableCases,
            int flakyCases,
            int consistentFailureCases,
            int inconclusiveCases,
            boolean allAttemptsVerified,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            Boolean allSourceSuitesPromotionEligible,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            Boolean statisticalConfidenceSatisfied
    ) {
        /**
         * Backward-compatible v1/v2 promotion constructor without statistical confidence.
         *
         * @param status eligibility status
         * @param reasons bounded reason codes
         * @param stableCases stable passing cases
         * @param flakyCases proven flaky cases
         * @param consistentFailureCases consistently failing cases
         * @param inconclusiveCases cases without complete proof
         * @param allAttemptsVerified complete source/child trust flag
         * @param allSourceSuitesPromotionEligible source-promotion closure flag
         */
        public PromotionVerdict(
                PromotionStatus status,
                List<String> reasons,
                int stableCases,
                int flakyCases,
                int consistentFailureCases,
                int inconclusiveCases,
                boolean allAttemptsVerified,
                Boolean allSourceSuitesPromotionEligible) {
            this(status, reasons, stableCases, flakyCases, consistentFailureCases,
                    inconclusiveCases, allAttemptsVerified,
                    allSourceSuitesPromotionEligible, null);
        }

        /** Freezes ordered reasons and rejects negative counters. */
        public PromotionVerdict {
            status = status == null ? PromotionStatus.BLOCKED : status;
            reasons = sortedStrings(reasons);
            if (stableCases < 0 || flakyCases < 0 || consistentFailureCases < 0
                    || inconclusiveCases < 0) {
                throw new IllegalArgumentException("Stability promotion counters must be non-negative");
            }
        }
    }

    /**
     * Non-destructive quarantine recommendation.
     *
     * @param status recommendation status
     * @param caseIds proven flaky case ids
     * @param reason stable reason code
     */
    public record QuarantineVerdict(
            QuarantineStatus status,
            List<String> caseIds,
            String reason
    ) {
        /** Normalizes the recommendation without authorizing a state change. */
        public QuarantineVerdict {
            status = status == null ? QuarantineStatus.UNDETERMINED : status;
            caseIds = sortedStrings(caseIds);
            reason = normalized(reason);
            if (status == QuarantineStatus.REQUIRED
                    && (caseIds.isEmpty() || !"FLAKY_CASE_OBSERVED".equals(reason))) {
                throw new IllegalArgumentException(
                        "Required quarantine must identify proven flaky cases");
            }
            if (status == QuarantineStatus.NOT_REQUIRED && !caseIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "A non-required quarantine cannot name flaky cases");
            }
        }
    }

    /**
     * Backward-compatible constructor for deterministic v1/v2 evidence without statistics.
     *
     * @param schemaVersion exact evidence generation, blank for v2
     * @param stabilityRunId deterministic analysis id
     * @param clientRequestId caller-owned idempotency key
     * @param suiteRef exact immutable suite revision
     * @param target exact suite target
     * @param requestedAttempts exact deterministic rerun count
     * @param status derived aggregate status
     * @param attempts exact source closure
     * @param caseResults exact case closure
     * @param promotion derived promotion verdict
     * @param quarantine derived quarantine recommendation
     * @param startedAt earliest source start
     * @param completedAt latest source completion
     * @param diagnostics bounded diagnostics
     * @param metadata bounded caller provenance
     */
    public TestSuiteStabilityEvidence(
            String schemaVersion,
            String stabilityRunId,
            String clientRequestId,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            TestSuite.Target target,
            int requestedAttempts,
            Status status,
            List<AttemptResult> attempts,
            List<CaseStabilityResult> caseResults,
            PromotionVerdict promotion,
            QuarantineVerdict quarantine,
            Instant startedAt,
            Instant completedAt,
            List<String> diagnostics,
            Map<String, Object> metadata) {
        this(schemaVersion, stabilityRunId, clientRequestId, suiteRef, target, requestedAttempts,
                status, attempts, caseResults, promotion, quarantine, null, startedAt, completedAt,
                diagnostics, metadata);
    }

    /** Re-derives aggregate truth and defensively freezes caller-owned values. */
    public TestSuiteStabilityEvidence {
        schemaVersion = defaulted(schemaVersion, statisticalAssessment == null
                ? SCHEMA_VERSION_V2 : versionFor(statisticalAssessment.policy()));
        stabilityRunId = normalized(stabilityRunId);
        clientRequestId = normalized(clientRequestId);
        status = status == null ? Status.INCONCLUSIVE : status;
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        caseResults = caseResults == null ? List.of() : List.copyOf(caseResults);
        diagnostics = sortedStrings(diagnostics);
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        if (!validMetadata(metadata)) {
            throw new IllegalArgumentException(
                    "Stability metadata must contain only bounded scalar provenance facts");
        }
        boolean sequential = SCHEMA_VERSION_V5.equals(schemaVersion);
        boolean statistical = Set.of(SCHEMA_VERSION_V3, SCHEMA_VERSION_V4, SCHEMA_VERSION_V5)
                .contains(schemaVersion);
        int maximumAttempts = statistical
                ? TestSuiteStabilityStatisticalPolicy.MAX_ATTEMPTS : MAX_ATTEMPTS;
        int observedAttempts = attempts.size();
        if (!List.of(SCHEMA_VERSION_V1, SCHEMA_VERSION_V2, SCHEMA_VERSION_V3,
                SCHEMA_VERSION_V4, SCHEMA_VERSION_V5)
                .contains(schemaVersion)
                || !STABILITY_RUN_ID.matcher(stabilityRunId).matches()
                || clientRequestId.isBlank() || suiteRef == null || target == null
                || requestedAttempts < MIN_ATTEMPTS || requestedAttempts > maximumAttempts
                || observedAttempts < (sequential ? 1 : MIN_ATTEMPTS)
                || sequential && observedAttempts > requestedAttempts
                || !sequential && observedAttempts != requestedAttempts
                || caseResults.isEmpty()
                || startedAt == null || completedAt == null || completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("Complete bounded stability evidence is required");
        }
        requireAttemptClosure(attempts, observedAttempts);
        requireCaseClosure(caseResults, observedAttempts);
        requireAttemptCaseConsistency(attempts, caseResults);
        if (!SCHEMA_VERSION_V1.equals(schemaVersion) && attempts.stream().anyMatch(value ->
                value.status() == AttemptStatus.VERIFIED
                        && value.sourcePromotionStatus() == null)) {
            throw new IllegalArgumentException(
                    "Stability evidence v2+ requires verified source-promotion closure");
        }
        Status derivedStatus = deriveStatus(caseResults);
        if (statistical && statisticalAssessment != null
                && !schemaVersion.equals(versionFor(statisticalAssessment.policy()))) {
            throw new IllegalArgumentException(
                    "Statistical evidence generation must match its probability model");
        }
        StatisticalAssessment derivedStatistics = statistical
                ? deriveStatisticalAssessment(statisticalAssessment == null
                ? null : statisticalAssessment.policy(), requestedAttempts, attempts, caseResults)
                : null;
        PromotionVerdict derivedPromotion;
        if (SCHEMA_VERSION_V1.equals(schemaVersion)) {
            derivedPromotion = deriveLegacyPromotion(attempts, caseResults, derivedStatus);
        } else if (SCHEMA_VERSION_V2.equals(schemaVersion)) {
            derivedPromotion = derivePromotion(attempts, caseResults, derivedStatus);
        } else {
            derivedPromotion = deriveStatisticalPromotion(
                    attempts, caseResults, derivedStatus, derivedStatistics);
        }
        QuarantineVerdict derivedQuarantine = deriveQuarantine(caseResults, derivedStatus);
        if (status != derivedStatus || !derivedPromotion.equals(promotion)
                || !derivedQuarantine.equals(quarantine)
                || !java.util.Objects.equals(derivedStatistics, statisticalAssessment)) {
            throw new IllegalArgumentException(
                    "Stability status, promotion, quarantine, and statistics must be server-derived");
        }
    }

    private static String versionFor(TestSuiteStabilityStatisticalPolicy policy) {
        if (policy == null) {
            return SCHEMA_VERSION_V2;
        }
        return switch (policy.model()) {
            case ZERO_INSTABILITY_EXACT_BINOMIAL -> SCHEMA_VERSION_V3;
            case BASELINE_CONDITIONAL_EXACT_BINOMIAL -> SCHEMA_VERSION_V4;
            case BASELINE_CONDITIONAL_ANYTIME_VALID_E_PROCESS -> SCHEMA_VERSION_V5;
        };
    }

    /**
     * Derives the only valid promotion verdict for a completed analysis.
     *
     * @param attempts exact source attempt closure
     * @param cases exact case stability closure
     * @param status already-derived aggregate status
     * @return deterministic promotion verdict
     */
    public static PromotionVerdict derivePromotion(
            List<AttemptResult> attempts,
            List<CaseStabilityResult> cases,
            Status status) {
        int stable = count(cases, CaseStatus.STABLE_PASS);
        int flaky = count(cases, CaseStatus.FLAKY);
        int failed = count(cases, CaseStatus.CONSISTENT_FAILURE);
        int incomplete = count(cases, CaseStatus.INCONCLUSIVE);
        boolean allAttemptsVerified = attempts != null && !attempts.isEmpty()
                && attempts.stream().allMatch(value -> value.status() == AttemptStatus.VERIFIED);
        boolean allSourceSuitesPromotionEligible = allAttemptsVerified
                && attempts.stream().allMatch(value -> value.sourcePromotionStatus()
                == TestSuiteRunEvidence.PromotionStatus.ELIGIBLE);
        boolean eligible = status == Status.STABLE && allAttemptsVerified
                && allSourceSuitesPromotionEligible;
        List<String> reasons = new ArrayList<>();
        if (flaky > 0) {
            reasons.add("FLAKY_CASE_OBSERVED");
        }
        if (failed > 0) {
            reasons.add("CONSISTENT_TEST_FAILURE");
        }
        if (incomplete > 0 || !allAttemptsVerified) {
            reasons.add("STABILITY_EVIDENCE_INCOMPLETE");
        }
        if (allAttemptsVerified && !allSourceSuitesPromotionEligible) {
            reasons.add("SOURCE_SUITE_PROMOTION_BLOCKED");
        }
        return new PromotionVerdict(eligible ? PromotionStatus.ELIGIBLE : PromotionStatus.BLOCKED,
                reasons, stable, flaky, failed, incomplete, allAttemptsVerified,
                allSourceSuitesPromotionEligible, null);
    }

    /**
     * Derives the v3-v5 release gate without allowing statistical confidence to replace correctness.
     *
     * @param attempts exact source attempt closure
     * @param cases exact case stability closure
     * @param status already-derived aggregate status
     * @param statistics independently derived statistical assessment
     * @return generation-matched statistical promotion verdict
     */
    public static PromotionVerdict deriveStatisticalPromotion(
            List<AttemptResult> attempts,
            List<CaseStabilityResult> cases,
            Status status,
            StatisticalAssessment statistics) {
        PromotionVerdict deterministic = derivePromotion(attempts, cases, status);
        boolean confidenceSatisfied = statistics != null
                && statistics.status() == StatisticalStatus.SATISFIED;
        boolean eligible = deterministic.status() == PromotionStatus.ELIGIBLE
                && confidenceSatisfied;
        List<String> reasons = new ArrayList<>(deterministic.reasons());
        if (!confidenceSatisfied) {
            reasons.add(statistics != null && statistics.status() == StatisticalStatus.REJECTED
                    ? "STATISTICAL_CONFIDENCE_REJECTED"
                    : "STATISTICAL_CONFIDENCE_INCONCLUSIVE");
        }
        return new PromotionVerdict(eligible ? PromotionStatus.ELIGIBLE : PromotionStatus.BLOCKED,
                reasons, deterministic.stableCases(), deterministic.flakyCases(),
                deterministic.consistentFailureCases(), deterministic.inconclusiveCases(),
                deterministic.allAttemptsVerified(),
                deterministic.allSourceSuitesPromotionEligible(), confidenceSatisfied);
    }

    private static PromotionVerdict deriveLegacyPromotion(
            List<AttemptResult> attempts,
            List<CaseStabilityResult> cases,
            Status status) {
        int stable = count(cases, CaseStatus.STABLE_PASS);
        int flaky = count(cases, CaseStatus.FLAKY);
        int failed = count(cases, CaseStatus.CONSISTENT_FAILURE);
        int incomplete = count(cases, CaseStatus.INCONCLUSIVE);
        boolean allAttemptsVerified = attempts != null && !attempts.isEmpty()
                && attempts.stream().allMatch(value -> value.status() == AttemptStatus.VERIFIED);
        List<String> reasons = new ArrayList<>();
        if (flaky > 0) {
            reasons.add("FLAKY_CASE_OBSERVED");
        }
        if (failed > 0) {
            reasons.add("CONSISTENT_TEST_FAILURE");
        }
        if (incomplete > 0 || !allAttemptsVerified) {
            reasons.add("STABILITY_EVIDENCE_INCOMPLETE");
        }
        return new PromotionVerdict(status == Status.STABLE && allAttemptsVerified
                ? PromotionStatus.ELIGIBLE : PromotionStatus.BLOCKED,
                reasons, stable, flaky, failed, incomplete, allAttemptsVerified, null, null);
    }

    /**
     * Reconstructs the generation-specific exact suite-level probability assessment.
     *
     * @param policy precommitted statistical policy
     * @param requestedAttempts complete fixed horizon or precommitted sequential maximum
     * @param attempts exact source attempt closure
     * @param cases exact case observation closure
     * @return deterministic generation-specific statistical assessment
     */
    public static StatisticalAssessment deriveStatisticalAssessment(
            TestSuiteStabilityStatisticalPolicy policy,
            int requestedAttempts,
            List<AttemptResult> attempts,
            List<CaseStabilityResult> cases) {
        boolean anytime = policy != null && policy.model()
                == TestSuiteStabilityStatisticalPolicy.Model
                .BASELINE_CONDITIONAL_ANYTIME_VALID_E_PROCESS;
        int observedAttempts = attempts == null ? 0 : attempts.size();
        if (policy == null || attempts == null || cases == null || cases.isEmpty()
                || requestedAttempts < policy.minimumRequiredAttempts()
                || !policy.horizonSufficient(requestedAttempts)
                || observedAttempts < (anytime ? 1 : MIN_ATTEMPTS)
                || observedAttempts > requestedAttempts
                || !anytime && requestedAttempts != observedAttempts) {
            throw new IllegalArgumentException(
                    "Statistical evidence requires a sufficient precommitted horizon");
        }
        if (anytime && !sequentialPrefixTerminal(
                policy, requestedAttempts, attempts, cases)) {
            throw new IllegalArgumentException(
                    "Sequential evidence must stop at its first terminal boundary");
        }
        int verified = (int) attempts.stream().filter(
                value -> value.status() == AttemptStatus.VERIFIED).count();
        int censored = observedAttempts - verified;
        int instabilityEvents = observedInstabilityEvents(observedAttempts, cases);
        if (policy.model()
                == TestSuiteStabilityStatisticalPolicy.Model.ZERO_INSTABILITY_EXACT_BINOMIAL) {
            StatisticalStatus status = instabilityEvents > 0
                    ? StatisticalStatus.REJECTED
                    : censored > 0 ? StatisticalStatus.INCONCLUSIVE : StatisticalStatus.SATISFIED;
            int achieved = status == StatisticalStatus.SATISFIED
                    ? policy.achievedConfidenceBps(verified) : 0;
            return new StatisticalAssessment(policy, policy.minimumRequiredAttempts(),
                    observedAttempts, verified, censored, instabilityEvents, achieved, status,
                    StatisticalStopReason.FIXED_HORIZON_REACHED,
                    STATISTICAL_MODEL_ASSUMPTIONS);
        }
        int comparisons = Math.max(0, verified - 1);
        if (anytime) {
            Integer firstCrossing = censored == 0
                    ? firstSequentialBoundaryCrossing(policy, observedAttempts, cases) : null;
            int firstCensoredAttempt = attempts.stream()
                    .filter(value -> value.status() != AttemptStatus.VERIFIED)
                    .mapToInt(AttemptResult::attempt).min().orElse(0);
            if ((censored > 0 && firstCensoredAttempt != observedAttempts)
                    || (censored == 0 && firstCrossing == null
                    && observedAttempts != requestedAttempts)
                    || (firstCrossing != null && firstCrossing != observedAttempts)) {
                throw new IllegalArgumentException(
                        "Sequential evidence must stop at its first terminal boundary");
            }
            StatisticalStatus status = censored > 0
                    ? StatisticalStatus.INCONCLUSIVE
                    : firstCrossing != null
                    ? StatisticalStatus.SATISFIED : StatisticalStatus.REJECTED;
            StatisticalStopReason stopReason = censored > 0
                    ? StatisticalStopReason.CENSORING_OBSERVED
                    : firstCrossing != null
                    ? StatisticalStopReason.E_VALUE_THRESHOLD_REACHED
                    : StatisticalStopReason.MAXIMUM_HORIZON_REACHED;
            int achieved = censored == 0
                    ? policy.sequentialAchievedConfidenceBps(
                    observedAttempts - 1, instabilityEvents) : 0;
            return new StatisticalAssessment(policy, policy.minimumRequiredAttempts(),
                    observedAttempts, verified, censored, instabilityEvents, achieved,
                    comparisons, null, firstCrossing, status, stopReason,
                    ANYTIME_VALID_MODEL_ASSUMPTIONS);
        }
        StatisticalStatus status = censored > 0
                ? StatisticalStatus.INCONCLUSIVE
                : policy.rateAdmissionSatisfied(comparisons, instabilityEvents)
                ? StatisticalStatus.SATISFIED : StatisticalStatus.REJECTED;
        int achieved = censored == 0
                ? policy.achievedConfidenceBps(comparisons, instabilityEvents) : 0;
        Integer upperRate = censored == 0
                ? policy.upperInstabilityRateBps(comparisons, instabilityEvents) : null;
        return new StatisticalAssessment(policy, policy.minimumRequiredAttempts(),
                observedAttempts, verified, censored, instabilityEvents, achieved,
                comparisons, upperRate, null, status,
                StatisticalStopReason.FIXED_HORIZON_REACHED,
                BASELINE_CONDITIONAL_MODEL_ASSUMPTIONS);
    }

    /**
     * Reports whether one ordered anytime-valid prefix is terminal under its signed policy.
     *
     * <p>The method returns false for a valid non-terminal prefix. Invalid policy, maximum, or
     * closure coordinates throw instead of being interpreted as permission to continue.</p>
     *
     * @param policy precommitted anytime-valid policy
     * @param requestedAttempts precommitted maximum execution horizon
     * @param attempts ordered observed source closure
     * @param cases ordered observed case closure
     * @return true at first censoring, first e-value crossing, or maximum horizon
     */
    public static boolean sequentialPrefixTerminal(
            TestSuiteStabilityStatisticalPolicy policy,
            int requestedAttempts,
            List<AttemptResult> attempts,
            List<CaseStabilityResult> cases) {
        if (policy == null || policy.model() != TestSuiteStabilityStatisticalPolicy.Model
                .BASELINE_CONDITIONAL_ANYTIME_VALID_E_PROCESS
                || attempts == null || cases == null || cases.isEmpty()
                || requestedAttempts < policy.minimumRequiredAttempts()
                || !policy.horizonSufficient(requestedAttempts)
                || attempts.isEmpty()
                || attempts.size() > requestedAttempts
                || !hasContiguousSequentialClosure(attempts, cases)) {
            throw new IllegalArgumentException(
                    "A complete anytime-valid prefix and maximum horizon are required");
        }
        int observedAttempts = attempts.size();
        int firstCensoredAttempt = attempts.stream()
                .filter(value -> value.status() != AttemptStatus.VERIFIED)
                .mapToInt(AttemptResult::attempt).min().orElse(0);
        if (firstCensoredAttempt > 0) {
            if (firstCensoredAttempt != observedAttempts) {
                throw new IllegalArgumentException(
                        "An anytime-valid prefix cannot continue after censoring");
            }
            return true;
        }
        Integer crossing = firstSequentialBoundaryCrossing(
                policy, observedAttempts, cases);
        if (crossing != null && crossing != observedAttempts) {
            throw new IllegalArgumentException(
                    "An anytime-valid prefix cannot continue after its first boundary crossing");
        }
        return crossing != null || observedAttempts == requestedAttempts;
    }

    private static boolean hasContiguousSequentialClosure(
            List<AttemptResult> attempts,
            List<CaseStabilityResult> cases) {
        for (int index = 0; index < attempts.size(); index++) {
            int expectedAttempt = index + 1;
            AttemptResult attempt = attempts.get(index);
            if (attempt == null || attempt.attempt() != expectedAttempt) {
                return false;
            }
            for (CaseStabilityResult result : cases) {
                if (result == null || result.observations().size() != attempts.size()
                        || result.observations().get(index).attempt() != expectedAttempt) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Integer firstSequentialBoundaryCrossing(
            TestSuiteStabilityStatisticalPolicy policy,
            int observedAttempts,
            List<CaseStabilityResult> cases) {
        List<String> baseline = null;
        int events = 0;
        for (int attemptIndex = 0; attemptIndex < observedAttempts; attemptIndex++) {
            List<String> vector = verifiedVector(cases, attemptIndex);
            if (vector == null) {
                return null;
            }
            if (baseline == null) {
                baseline = vector;
                continue;
            }
            if (!baseline.equals(vector)) {
                events++;
            }
            int executionAttempt = attemptIndex + 1;
            if (executionAttempt >= policy.minimumRequiredAttempts()
                    && policy.sequentialAdmissionSatisfied(attemptIndex, events)) {
                return executionAttempt;
            }
        }
        return null;
    }

    private static List<String> verifiedVector(
            List<CaseStabilityResult> cases,
            int attemptIndex) {
        List<String> vector = new ArrayList<>();
        for (CaseStabilityResult result : cases) {
            CaseObservation observation = result.observations().get(attemptIndex);
            if (observation.status() != ObservationStatus.VERIFIED) {
                return null;
            }
            vector.add(result.caseId() + ':' + observation.outcomeIdentity());
        }
        return List.copyOf(vector);
    }

    /**
     * Derives a non-destructive quarantine recommendation.
     *
     * @param cases exact case stability closure
     * @param status already-derived aggregate status
     * @return deterministic recommendation
     */
    public static QuarantineVerdict deriveQuarantine(
            List<CaseStabilityResult> cases,
            Status status) {
        List<String> flaky = cases == null ? List.of() : cases.stream()
                .filter(value -> value.status() == CaseStatus.FLAKY)
                .map(CaseStabilityResult::caseId).sorted().toList();
        if (!flaky.isEmpty()) {
            return new QuarantineVerdict(
                    QuarantineStatus.REQUIRED, flaky, "FLAKY_CASE_OBSERVED");
        }
        return status == Status.INCONCLUSIVE
                ? new QuarantineVerdict(QuarantineStatus.UNDETERMINED, List.of(),
                "STABILITY_EVIDENCE_INCOMPLETE")
                : new QuarantineVerdict(QuarantineStatus.NOT_REQUIRED, List.of(), "");
    }

    private static int observedInstabilityEvents(
            int requestedAttempts,
            List<CaseStabilityResult> cases) {
        List<String> baseline = null;
        int events = 0;
        for (int attemptIndex = 0; attemptIndex < requestedAttempts; attemptIndex++) {
            List<String> vector = new ArrayList<>();
            boolean verified = true;
            for (CaseStabilityResult result : cases) {
                CaseObservation observation = result.observations().get(attemptIndex);
                if (observation.status() != ObservationStatus.VERIFIED) {
                    verified = false;
                    break;
                }
                vector.add(result.caseId() + ':' + observation.outcomeIdentity());
            }
            if (!verified) {
                continue;
            }
            if (baseline == null) {
                baseline = List.copyOf(vector);
            } else if (!baseline.equals(vector)) {
                events++;
            }
        }
        return events;
    }

    private static CaseStatus deriveCaseStatus(
            List<CaseObservation> observations,
            Set<String> outcomes) {
        if (outcomes.size() > 1) {
            return CaseStatus.FLAKY;
        }
        boolean complete = !observations.isEmpty() && observations.stream()
                .allMatch(value -> value.status() == ObservationStatus.VERIFIED);
        if (!complete || outcomes.isEmpty()) {
            return CaseStatus.INCONCLUSIVE;
        }
        return observations.stream().allMatch(
                value -> value.evidenceStatus() == TestRunEvidence.Status.PASSED)
                ? CaseStatus.STABLE_PASS : CaseStatus.CONSISTENT_FAILURE;
    }

    private static Status deriveStatus(List<CaseStabilityResult> cases) {
        if (cases.stream().anyMatch(value -> value.status() == CaseStatus.FLAKY)) {
            return Status.FLAKY;
        }
        if (cases.stream().anyMatch(value -> value.status() == CaseStatus.INCONCLUSIVE)) {
            return Status.INCONCLUSIVE;
        }
        if (cases.stream().anyMatch(
                value -> value.status() == CaseStatus.CONSISTENT_FAILURE)) {
            return Status.CONSISTENT_FAILURE;
        }
        return Status.STABLE;
    }

    private static void requireAttemptClosure(List<AttemptResult> attempts, int requested) {
        for (int index = 0; index < requested; index++) {
            if (attempts.get(index).attempt() != index + 1) {
                throw new IllegalArgumentException(
                        "Stability attempts must form an exact ordered closure");
            }
        }
    }

    private static void requireCaseClosure(List<CaseStabilityResult> cases, int requested) {
        Set<String> ids = new LinkedHashSet<>();
        for (CaseStabilityResult result : cases) {
            if (!ids.add(result.caseId()) || result.observations().size() != requested) {
                throw new IllegalArgumentException(
                        "Stability cases must be unique and cover every requested attempt");
            }
            for (int index = 0; index < requested; index++) {
                if (result.observations().get(index).attempt() != index + 1) {
                    throw new IllegalArgumentException(
                            "Case observations must form an exact ordered attempt closure");
                }
            }
        }
    }

    private static void requireAttemptCaseConsistency(
            List<AttemptResult> attempts,
            List<CaseStabilityResult> cases) {
        for (int attemptIndex = 0; attemptIndex < attempts.size(); attemptIndex++) {
            boolean allCasesVerified = true;
            for (CaseStabilityResult result : cases) {
                if (result.observations().get(attemptIndex).status()
                        != ObservationStatus.VERIFIED) {
                    allCasesVerified = false;
                    break;
                }
            }
            if ((attempts.get(attemptIndex).status() == AttemptStatus.VERIFIED)
                    != allCasesVerified) {
                throw new IllegalArgumentException(
                        "Attempt trust status must match the complete case-observation closure");
            }
        }
    }

    private static int count(List<CaseStabilityResult> cases, CaseStatus status) {
        return (int) cases.stream().filter(value -> value.status() == status).count();
    }

    private static List<String> sortedStrings(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> sorted = new ArrayList<>(new LinkedHashSet<>(values));
        sorted.removeIf(value -> normalized(value).isBlank());
        sorted.replaceAll(TestSuiteStabilityEvidence::normalized);
        sorted.sort(Comparator.naturalOrder());
        return List.copyOf(sorted);
    }

    private static boolean fingerprint(String value) {
        return FINGERPRINT.matcher(normalized(value)).matches();
    }

    private static boolean validMetadata(Map<String, Object> metadata) {
        return metadata.size() <= MAX_METADATA_PROPERTIES
                && metadata.entrySet().stream().allMatch(entry -> entry.getKey() != null
                && METADATA_KEY.matcher(entry.getKey()).matches()
                && metadataValue(entry.getValue()));
    }

    private static boolean metadataValue(Object value) {
        if (value instanceof Double number) {
            return Double.isFinite(number);
        }
        if (value instanceof Float number) {
            return Float.isFinite(number);
        }
        return value instanceof Boolean || value instanceof Number
                || value instanceof String text && text.length() <= MAX_METADATA_STRING_LENGTH;
    }

    private static String defaulted(String value, String fallback) {
        String safe = normalized(value);
        return safe.isBlank() ? fallback : safe;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
