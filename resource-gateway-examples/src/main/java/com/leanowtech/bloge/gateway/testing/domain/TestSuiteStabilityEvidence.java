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
        Instant startedAt,
        Instant completedAt,
        List<String> diagnostics,
        Map<String, Object> metadata
) {
    /** Historical stability evidence version without source-promotion closure. */
    public static final String SCHEMA_VERSION_V1 = "bloge.testSuiteStabilityEvidence.v1";
    /** Current stability evidence protocol version with source-promotion closure. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteStabilityEvidence.v2";
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
            Boolean allSourceSuitesPromotionEligible
    ) {
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

    /** Re-derives aggregate truth and defensively freezes caller-owned values. */
    public TestSuiteStabilityEvidence {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
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
        if (!List.of(SCHEMA_VERSION_V1, SCHEMA_VERSION).contains(schemaVersion)
                || !STABILITY_RUN_ID.matcher(stabilityRunId).matches()
                || clientRequestId.isBlank() || suiteRef == null || target == null
                || requestedAttempts < MIN_ATTEMPTS || requestedAttempts > MAX_ATTEMPTS
                || attempts.size() != requestedAttempts || caseResults.isEmpty()
                || startedAt == null || completedAt == null || completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("Complete bounded stability evidence is required");
        }
        requireAttemptClosure(attempts, requestedAttempts);
        requireCaseClosure(caseResults, requestedAttempts);
        if (SCHEMA_VERSION.equals(schemaVersion) && attempts.stream().anyMatch(value ->
                value.status() == AttemptStatus.VERIFIED
                        && value.sourcePromotionStatus() == null)) {
            throw new IllegalArgumentException(
                    "Stability evidence v2 requires verified source-promotion closure");
        }
        Status derivedStatus = deriveStatus(caseResults);
        PromotionVerdict derivedPromotion = SCHEMA_VERSION_V1.equals(schemaVersion)
                ? deriveLegacyPromotion(attempts, caseResults, derivedStatus)
                : derivePromotion(attempts, caseResults, derivedStatus);
        QuarantineVerdict derivedQuarantine = deriveQuarantine(caseResults, derivedStatus);
        if (status != derivedStatus || !derivedPromotion.equals(promotion)
                || !derivedQuarantine.equals(quarantine)) {
            throw new IllegalArgumentException(
                    "Stability status, promotion, and quarantine must be server-derived");
        }
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
                allSourceSuitesPromotionEligible);
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
                reasons, stable, flaky, failed, incomplete, allAttemptsVerified, null);
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
