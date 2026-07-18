package com.leanowtech.bloge.gateway.testing.domain;

import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Payload-free aggregate evidence for one exact pure-DSL mutation suite execution.
 *
 * <p>V5 separates mutation quality from target failure. A mutant is killed only when at least one
 * independently verified child run ends in {@link TestRunEvidence.Status#ASSERTION_FAILED}.
 * Timeout, fixture, control-plan, target, persistence, and signature failures are inconclusive and
 * can never inflate the mutation score. The baseline oracle must pass before any score is usable.</p>
 *
 * <p>Generation one never claims semantic equivalence and never removes a mutant from the score as
 * equivalent. Inconclusive mutants are excluded from the numeric denominator but remain explicitly
 * counted and bounded by the immutable suite policy. Unclassified mutants make the verdict
 * incomplete, even when the already observed numerator would otherwise meet the threshold.</p>
 *
 * @param schemaVersion exact mutation evidence generation
 * @param suiteRunId durable aggregate run id
 * @param clientRequestId caller idempotency key
 * @param status aggregate lifecycle state
 * @param executionPurpose fixed mutation-suite purpose
 * @param suiteRef exact immutable V5 suite revision
 * @param target exact baseline graph target
 * @param startedAt authoritative start time
 * @param completedAt terminal time or null while running
 * @param caseResults ordered baseline oracle child results
 * @param coverage baseline structural coverage verdict
 * @param promotion combined baseline and mutation promotion verdict
 * @param evaluationMode fixed pure-DSL mutation mode
 * @param sourceFormat exact recoverable source format
 * @param baselineSourceFingerprint exact baseline source fingerprint
 * @param baselineGraphArtifactFingerprint exact baseline graph artifact
 * @param mutationPlanFingerprint exact reviewed mutation plan
 * @param mutationPolicy exact planner and compiler proof policy
 * @param sourcePlanStatus complete or explicitly accepted partial source plan
 * @param planningGapsAccepted whether disclosed planning gaps were accepted
 * @param planningGaps stable payload-free planning limitations
 * @param oracleSuiteRef exact immutable business oracle suite
 * @param baselineStatus typed baseline oracle outcome
 * @param mutantResults ordered complete mutant execution closure
 * @param mutationScore server-derived score and gate verdict
 * @param diagnostics bounded aggregate diagnostics
 * @param metadata bounded scope provenance without business payloads
 */
public record TestSuiteRunEvidenceV5(
        String schemaVersion,
        String suiteRunId,
        String clientRequestId,
        TestSuiteRunEvidence.Status status,
        String executionPurpose,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        TestSuite.Target target,
        Instant startedAt,
        Instant completedAt,
        List<TestSuiteRunEvidence.CaseResult> caseResults,
        TestSuiteRunEvidence.CoverageVerdict coverage,
        TestSuiteRunEvidence.PromotionVerdict promotion,
        TestSuiteV5.EvaluationMode evaluationMode,
        String sourceFormat,
        String baselineSourceFingerprint,
        String baselineGraphArtifactFingerprint,
        String mutationPlanFingerprint,
        TestSuiteV5.MutationPolicy mutationPolicy,
        TestSuiteV5.SourcePlanStatus sourcePlanStatus,
        boolean planningGapsAccepted,
        List<TestSuiteV5.PlanningGap> planningGaps,
        TestSuiteV5.OracleSuiteRef oracleSuiteRef,
        BaselineStatus baselineStatus,
        List<MutantResult> mutantResults,
        MutationScoreVerdict mutationScore,
        List<String> diagnostics,
        Map<String, Object> metadata
) implements TestSuiteRunEvidenceProtocol {
    /** Current pure-DSL mutation aggregate evidence generation. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteRunEvidence.v5";
    /** Fixed authorization purpose for mutation execution. */
    public static final String EXECUTION_PURPOSE = "MUTATION_SUITE_EXECUTION";

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern MACHINE_CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    /** Normalizes protocol values and validates the complete baseline, mutant, and score closure. */
    public TestSuiteRunEvidenceV5 {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        suiteRunId = normalized(suiteRunId);
        clientRequestId = normalized(clientRequestId);
        status = status == null ? TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE : status;
        executionPurpose = normalized(executionPurpose);
        caseResults = caseResults == null ? List.of() : List.copyOf(caseResults);
        coverage = coverage == null
                ? TestSuiteRunEvidence.CoverageVerdict.notEvaluated() : coverage;
        promotion = promotion == null
                ? TestSuiteRunEvidence.PromotionVerdict.notEvaluated() : promotion;
        evaluationMode = evaluationMode == null
                ? TestSuiteV5.EvaluationMode.PURE_DSL_MUTATION : evaluationMode;
        sourceFormat = normalized(sourceFormat);
        baselineSourceFingerprint = normalized(baselineSourceFingerprint);
        baselineGraphArtifactFingerprint = normalized(baselineGraphArtifactFingerprint);
        mutationPlanFingerprint = normalized(mutationPlanFingerprint);
        mutationPolicy = Objects.requireNonNull(mutationPolicy, "mutationPolicy");
        sourcePlanStatus = Objects.requireNonNull(sourcePlanStatus, "sourcePlanStatus");
        planningGaps = planningGaps == null ? List.of() : List.copyOf(planningGaps);
        oracleSuiteRef = Objects.requireNonNull(oracleSuiteRef, "oracleSuiteRef");
        baselineStatus = Objects.requireNonNull(baselineStatus, "baselineStatus");
        mutantResults = mutantResults == null ? List.of() : List.copyOf(mutantResults);
        mutationScore = Objects.requireNonNull(mutationScore, "mutationScore");
        diagnostics = immutableDiagnostics(diagnostics);
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));

        boolean planShape = sourcePlanStatus == TestSuiteV5.SourcePlanStatus.GENERATED
                ? planningGaps.isEmpty() && !planningGapsAccepted
                : !planningGaps.isEmpty() && planningGapsAccepted;
        if (!SCHEMA_VERSION.equals(schemaVersion) || suiteRunId.isBlank()
                || clientRequestId.isBlank() || suiteRef == null || target == null
                || !"GRAPH".equals(target.kind()) || caseResults.isEmpty()
                || mutantResults.isEmpty() || startedAt == null
                || !EXECUTION_PURPOSE.equals(executionPurpose)
                || evaluationMode != TestSuiteV5.EvaluationMode.PURE_DSL_MUTATION
                || !TestSuiteV5.SOURCE_FORMAT.equals(sourceFormat)
                || !fingerprint(baselineSourceFingerprint)
                || !fingerprint(baselineGraphArtifactFingerprint)
                || !fingerprint(mutationPlanFingerprint) || !planShape) {
            throw new IllegalArgumentException(
                    "Mutation evidence requires exact baseline, plan, target, and execution identity");
        }
        if ((status == TestSuiteRunEvidence.Status.RUNNING) != (completedAt == null)) {
            throw new IllegalArgumentException(
                    "Mutation evidence completion time must match aggregate lifecycle");
        }
        validateBaseline(caseResults, baselineStatus, status);
        validateMutantClosure(caseResults, mutantResults, mutationPolicy);
        MutationScoreVerdict derived = score(
                baselineStatus, mutantResults, mutationScore.policy());
        if (!derived.equals(mutationScore)) {
            throw new IllegalArgumentException(
                    "Mutation score must be derived from the complete typed mutant closure");
        }
        validateLifecycle(status, baselineStatus, mutationScore.status(), promotion, diagnostics);
        if (promotion.status() == TestSuiteRunEvidence.PromotionStatus.ELIGIBLE
                && (status != TestSuiteRunEvidence.Status.PASSED
                || coverage.status() != TestSuiteRunEvidence.CoverageStatus.SATISFIED
                || mutationScore.status() != MutationScoreStatus.SATISFIED)) {
            throw new IllegalArgumentException(
                    "Mutation promotion requires passing baseline coverage and score evidence");
        }
    }

    /** Typed state of the unmodified graph under the exact oracle closure. */
    public enum BaselineStatus {
        /** No baseline child has completed. */
        PENDING,
        /** Some baseline children completed while the aggregate checkpoint remains active. */
        RUNNING,
        /** Every baseline oracle child passed with signed assertion evidence. */
        PASSED,
        /** A complete baseline closure contains a business assertion or execution failure. */
        FAILED,
        /** The baseline closure cannot be independently verified. */
        EVIDENCE_INCOMPLETE
    }

    /** Typed interpretation of one mutant against one immutable oracle case. */
    public enum MutantCaseStatus {
        /** Child scheduling has not started. */
        PENDING,
        /** A signed assertion failure proves this case killed the mutant. */
        ASSERTION_KILLED,
        /** The mutant passed every assertion in this case. */
        SURVIVED,
        /** Target, fixture, timeout, or control failure cannot establish a kill. */
        EXECUTION_FAILED,
        /** Child evidence is absent, unsigned, corrupt, or incomplete. */
        EVIDENCE_INCOMPLETE,
        /** Scheduling was deliberately or fail-closedly stopped. */
        NOT_SCHEDULED
    }

    /** Aggregate classification of one exact mutant. */
    public enum MutantStatus {
        /** No oracle case has started. */
        PENDING,
        /** At least one case completed while later cases remain pending. */
        RUNNING,
        /** At least one independently verified assertion failure killed the mutant. */
        KILLED,
        /** Every oracle case passed against the mutant. */
        SURVIVED,
        /** No assertion killed the mutant and at least one result was not a valid pass. */
        INCONCLUSIVE,
        /** The complete mutant was skipped before its first child. */
        NOT_SCHEDULED
    }

    /** Mutation score and gate evaluation state. */
    public enum MutationScoreStatus {
        /** Baseline execution has not yet established an oracle. */
        NOT_EVALUATED,
        /** Every mutant is classified and the frozen score policy is satisfied. */
        SATISFIED,
        /** Every mutant is classified but the baseline or score policy failed. */
        UNSATISFIED,
        /** Baseline or mutant evidence is incomplete. */
        INCOMPLETE
    }

    /**
     * Payload-free signed child interpretation for one mutant and oracle case.
     *
     * @param caseId exact oracle case id
     * @param fixtureBundleRef exact immutable fixture dependency
     * @param mutantTargetFingerprint expected exact regenerated mutant target
     * @param status typed case interpretation
     * @param runId signed child run id when available
     * @param evidenceFingerprint canonical child evidence fingerprint when available
     * @param evidenceStatus exact child status when available
     * @param evidenceClass exact child certification class when available
     * @param assertionsEvaluated governed assertion count
     * @param assertionsPassed passing assertion count
     * @param diagnosticCode bounded stable failure code
     */
    public record MutantCaseResult(
            String caseId,
            TestSuite.FixtureBundleRef fixtureBundleRef,
            String mutantTargetFingerprint,
            MutantCaseStatus status,
            String runId,
            String evidenceFingerprint,
            TestRunEvidence.Status evidenceStatus,
            TestRunEvidence.EvidenceClass evidenceClass,
            int assertionsEvaluated,
            int assertionsPassed,
            String diagnosticCode
    ) {
        /** Rejects false kill, survival, and child-integrity claims. */
        public MutantCaseResult {
            caseId = normalized(caseId);
            mutantTargetFingerprint = normalized(mutantTargetFingerprint);
            status = Objects.requireNonNull(status, "status");
            runId = normalized(runId);
            evidenceFingerprint = normalized(evidenceFingerprint);
            diagnosticCode = normalized(diagnosticCode);
            if (caseId.isBlank() || fixtureBundleRef == null
                    || !fingerprint(mutantTargetFingerprint)
                    || assertionsEvaluated < 0 || assertionsPassed < 0
                    || assertionsPassed > assertionsEvaluated
                    || !diagnosticCode.isBlank()
                    && !MACHINE_CODE.matcher(diagnosticCode).matches()) {
                throw new IllegalArgumentException("Mutation case result is malformed");
            }
            boolean completeChild = !runId.isBlank() && fingerprint(evidenceFingerprint)
                    && evidenceStatus != null && evidenceClass != null;
            boolean noChild = runId.isBlank() && evidenceFingerprint.isBlank()
                    && evidenceStatus == null && evidenceClass == null;
            if (!completeChild && !noChild) {
                throw new IllegalArgumentException(
                        "Mutation child identity must be complete or absent");
            }
            switch (status) {
                case ASSERTION_KILLED -> {
                    if (!completeChild || evidenceStatus != TestRunEvidence.Status.ASSERTION_FAILED
                            || assertionsEvaluated < 1 || assertionsPassed >= assertionsEvaluated) {
                        throw new IllegalArgumentException(
                                "Only signed assertion failure may kill a mutant");
                    }
                }
                case SURVIVED -> {
                    if (!completeChild || evidenceStatus != TestRunEvidence.Status.PASSED
                            || assertionsEvaluated < 1 || assertionsPassed != assertionsEvaluated
                            || !diagnosticCode.isBlank()) {
                        throw new IllegalArgumentException(
                                "A survived mutation case requires passing assertion evidence");
                    }
                }
                case EXECUTION_FAILED -> {
                    if (!completeChild || List.of(TestRunEvidence.Status.PASSED,
                            TestRunEvidence.Status.ASSERTION_FAILED,
                            TestRunEvidence.Status.EVIDENCE_INCOMPLETE).contains(evidenceStatus)
                            || diagnosticCode.isBlank()) {
                        throw new IllegalArgumentException(
                                "Mutation execution failure must remain distinct from a kill");
                    }
                }
                case EVIDENCE_INCOMPLETE -> {
                    if ((completeChild
                            && evidenceStatus != TestRunEvidence.Status.EVIDENCE_INCOMPLETE)
                            || diagnosticCode.isBlank()) {
                        throw new IllegalArgumentException(
                                "Incomplete mutation evidence requires a bounded diagnostic");
                    }
                }
                case PENDING -> {
                    if (!noChild || assertionsEvaluated != 0 || assertionsPassed != 0
                            || !diagnosticCode.isBlank()) {
                        throw new IllegalArgumentException(
                                "Pending mutation case cannot claim child evidence");
                    }
                }
                case NOT_SCHEDULED -> {
                    if (!noChild || assertionsEvaluated != 0 || assertionsPassed != 0
                            || diagnosticCode.isBlank()) {
                        throw new IllegalArgumentException(
                                "Unscheduled mutation case requires a bounded reason");
                    }
                }
            }
        }
    }

    /**
     * Complete typed result for one exact planned mutant.
     *
     * @param mutant exact immutable mutation coordinate
     * @param status server-derived classification
     * @param caseResults one ordered result for every oracle case
     * @param killingCaseIds ordered cases carrying signed assertion kills
     */
    public record MutantResult(
            TestSuiteV5.MutantRef mutant,
            MutantStatus status,
            List<MutantCaseResult> caseResults,
            List<String> killingCaseIds
    ) {
        /** Validates classification and kill provenance from the complete case closure. */
        public MutantResult {
            mutant = Objects.requireNonNull(mutant, "mutant");
            status = Objects.requireNonNull(status, "status");
            caseResults = caseResults == null ? List.of() : List.copyOf(caseResults);
            killingCaseIds = killingCaseIds == null ? List.of() : List.copyOf(killingCaseIds);
            if (caseResults.isEmpty() || !status.equals(classify(caseResults))) {
                throw new IllegalArgumentException(
                        "Mutant classification must be derived from its complete case closure");
            }
            List<String> derivedKills = caseResults.stream()
                    .filter(result -> result.status() == MutantCaseStatus.ASSERTION_KILLED)
                    .map(MutantCaseResult::caseId).toList();
            if (!derivedKills.equals(killingCaseIds)) {
                throw new IllegalArgumentException(
                        "Mutant killing cases must match signed assertion-failure results");
            }
            // COLLECT_ALL may retain a proven kill while later diagnostic cases are still pending.
            // The RUNNING classification keeps the score incomplete until that case row closes.
        }
    }

    /**
     * Deterministic mutation score and policy verdict.
     *
     * @param status score evaluation state
     * @param policy exact immutable threshold policy
     * @param plannedMutants complete planned mutant count
     * @param killedMutants assertion-killed count
     * @param survivedMutants fully passing count
     * @param inconclusiveMutants terminal inconclusive count
     * @param unclassifiedMutants pending, running, or unscheduled count
     * @param denominatorMutants killed plus survived; inconclusive is never silently counted
     * @param scoreBasisPoints floor(killed * 10000 / denominator), or zero while incomplete
     * @param equivalentMutantsExcluded fixed zero in generation one
     * @param reasons stable fail-closed policy reasons
     */
    public record MutationScoreVerdict(
            MutationScoreStatus status,
            TestSuiteV5.MutationScorePolicy policy,
            int plannedMutants,
            int killedMutants,
            int survivedMutants,
            int inconclusiveMutants,
            int unclassifiedMutants,
            int denominatorMutants,
            int scoreBasisPoints,
            int equivalentMutantsExcluded,
            List<String> reasons
    ) {
        /** Validates count closure and generation-one denominator honesty. */
        public MutationScoreVerdict {
            status = Objects.requireNonNull(status, "status");
            policy = Objects.requireNonNull(policy, "policy");
            reasons = immutableDiagnostics(reasons);
            if (plannedMutants < 1 || plannedMutants > TestSuiteV5.MAX_MUTANTS
                    || killedMutants < 0 || survivedMutants < 0
                    || inconclusiveMutants < 0 || unclassifiedMutants < 0
                    || plannedMutants != killedMutants + survivedMutants
                    + inconclusiveMutants + unclassifiedMutants
                    || denominatorMutants != killedMutants + survivedMutants
                    || scoreBasisPoints < 0 || scoreBasisPoints > 10_000
                    || equivalentMutantsExcluded != 0
                    || policy.excludeEquivalentMutants()) {
                throw new IllegalArgumentException("Mutation score count closure is invalid");
            }
            int expectedScore = unclassifiedMutants > 0 || denominatorMutants == 0
                    ? 0 : (int) ((long) killedMutants * 10_000 / denominatorMutants);
            if (scoreBasisPoints != expectedScore) {
                throw new IllegalArgumentException(
                        "Mutation score must use the frozen generation-one denominator");
            }
        }
    }

    /**
     * Derives one mutant classification from its ordered case closure.
     *
     * @param cases exact one-mutant oracle results
     * @return deterministic fail-closed classification
     */
    public static MutantStatus classify(List<MutantCaseResult> cases) {
        List<MutantCaseResult> safe = cases == null ? List.of() : List.copyOf(cases);
        if (safe.isEmpty()) {
            throw new IllegalArgumentException("A mutant requires at least one oracle case");
        }
        if (safe.stream().allMatch(result -> result.status() == MutantCaseStatus.PENDING)) {
            return MutantStatus.PENDING;
        }
        if (safe.stream().anyMatch(result -> result.status() == MutantCaseStatus.PENDING)) {
            return MutantStatus.RUNNING;
        }
        if (safe.stream().anyMatch(
                result -> result.status() == MutantCaseStatus.ASSERTION_KILLED)) {
            return MutantStatus.KILLED;
        }
        if (safe.stream().anyMatch(
                result -> result.status() == MutantCaseStatus.NOT_SCHEDULED)) {
            return MutantStatus.NOT_SCHEDULED;
        }
        if (safe.stream().allMatch(result -> result.status() == MutantCaseStatus.SURVIVED)) {
            return MutantStatus.SURVIVED;
        }
        return MutantStatus.INCONCLUSIVE;
    }

    /**
     * Derives the score without reading child payloads or trusting caller-supplied counts.
     *
     * @param baseline exact baseline oracle status
     * @param mutants complete ordered mutant results
     * @param policy immutable suite score policy
     * @return deterministic generation-one score verdict
     */
    public static MutationScoreVerdict score(
            BaselineStatus baseline,
            List<MutantResult> mutants,
            TestSuiteV5.MutationScorePolicy policy) {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(policy, "policy");
        List<MutantResult> safe = mutants == null ? List.of() : List.copyOf(mutants);
        if (safe.isEmpty()) {
            throw new IllegalArgumentException("Mutation score requires a planned mutant closure");
        }
        int killed = count(safe, MutantStatus.KILLED);
        int survived = count(safe, MutantStatus.SURVIVED);
        int inconclusive = count(safe, MutantStatus.INCONCLUSIVE);
        int unclassified = safe.size() - killed - survived - inconclusive;
        int denominator = killed + survived;
        int basisPoints = unclassified > 0 || denominator == 0
                ? 0 : (int) ((long) killed * 10_000 / denominator);
        List<String> reasons = new ArrayList<>();
        MutationScoreStatus scoreStatus;
        if (baseline == BaselineStatus.PENDING || baseline == BaselineStatus.RUNNING) {
            reasons.add("BASELINE_NOT_EVALUATED");
            scoreStatus = MutationScoreStatus.NOT_EVALUATED;
        } else if (baseline == BaselineStatus.EVIDENCE_INCOMPLETE) {
            reasons.add("BASELINE_EVIDENCE_INCOMPLETE");
            scoreStatus = MutationScoreStatus.INCOMPLETE;
        } else if (baseline == BaselineStatus.FAILED) {
            reasons.add("BASELINE_ORACLE_FAILED");
            scoreStatus = MutationScoreStatus.UNSATISFIED;
        } else if (unclassified > 0) {
            reasons.add("MUTANT_CLASSIFICATION_INCOMPLETE");
            scoreStatus = MutationScoreStatus.INCOMPLETE;
        } else {
            if (basisPoints < policy.minimumScoreBasisPoints()) {
                reasons.add("MUTATION_SCORE_BELOW_THRESHOLD");
            }
            if (inconclusive > policy.maximumInconclusiveMutants()) {
                reasons.add("MUTATION_INCONCLUSIVE_LIMIT_EXCEEDED");
            }
            if (policy.requireNoSurvivors() && survived > 0) {
                reasons.add("MUTATION_SURVIVOR_FORBIDDEN");
            }
            scoreStatus = reasons.isEmpty()
                    ? MutationScoreStatus.SATISFIED : MutationScoreStatus.UNSATISFIED;
        }
        return new MutationScoreVerdict(scoreStatus, policy, safe.size(), killed, survived,
                inconclusive, unclassified, denominator, basisPoints, 0, reasons);
    }

    private static void validateBaseline(
            List<TestSuiteRunEvidence.CaseResult> cases,
            BaselineStatus baseline,
            TestSuiteRunEvidence.Status aggregateStatus) {
        if (cases.size() > TestSuiteV5.MAX_CASES
                || cases.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Mutation baseline case closure is invalid");
        }
        Set<String> caseIds = new LinkedHashSet<>();
        cases.forEach(result -> {
            if (!caseIds.add(result.caseId())) {
                throw new IllegalArgumentException("Mutation baseline case ids must be unique");
            }
        });
        boolean allPending = cases.stream().allMatch(result ->
                result.status() == TestSuiteRunEvidence.CaseStatus.PENDING);
        boolean somePending = cases.stream().anyMatch(result ->
                result.status() == TestSuiteRunEvidence.CaseStatus.PENDING);
        boolean someUnscheduled = cases.stream().anyMatch(result ->
                result.status() == TestSuiteRunEvidence.CaseStatus.NOT_SCHEDULED);
        boolean allPassed = cases.stream().allMatch(result ->
                result.status() == TestSuiteRunEvidence.CaseStatus.PASSED);
        boolean someIncomplete = cases.stream().anyMatch(result ->
                result.status() == TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE);
        BaselineStatus derived;
        if (allPending) {
            derived = BaselineStatus.PENDING;
        } else if (aggregateStatus == TestSuiteRunEvidence.Status.RUNNING && somePending) {
            derived = BaselineStatus.RUNNING;
        } else if (allPassed) {
            derived = BaselineStatus.PASSED;
        } else if (someIncomplete || somePending || someUnscheduled) {
            derived = BaselineStatus.EVIDENCE_INCOMPLETE;
        } else {
            derived = BaselineStatus.FAILED;
        }
        if (baseline != derived) {
            throw new IllegalArgumentException(
                    "Mutation baseline status must be derived from its exact case closure");
        }
    }

    private static void validateMutantClosure(
            List<TestSuiteRunEvidence.CaseResult> baselineCases,
            List<MutantResult> mutants,
            TestSuiteV5.MutationPolicy policy) {
        if (mutants.size() > TestSuiteV5.MAX_MUTANTS
                || mutants.size() > policy.maxMutants()
                || (long) mutants.size() * baselineCases.size()
                > TestSuiteV5.MAX_MUTANT_CASE_EXECUTIONS) {
            throw new IllegalArgumentException("Mutation evidence work closure exceeds its bounds");
        }
        List<String> baselineIds = baselineCases.stream()
                .map(TestSuiteRunEvidence.CaseResult::caseId).toList();
        Map<String, TestSuite.FixtureBundleRef> baselineFixtures = new LinkedHashMap<>();
        baselineCases.forEach(result -> baselineFixtures.put(
                result.caseId(), result.fixtureBundleRef()));
        Set<String> sourceFingerprints = new LinkedHashSet<>();
        for (int index = 0; index < mutants.size(); index++) {
            MutantResult result = Objects.requireNonNull(mutants.get(index), "mutantResult");
            TestSuiteV5.MutantRef mutant = result.mutant();
            if (!("mutant-%03d".formatted(index + 1)).equals(mutant.mutantId())
                    || !sourceFingerprints.add(mutant.mutantSourceFingerprint())) {
                throw new IllegalArgumentException(
                        "Mutation evidence requires the ordered unique plan closure");
            }
            List<String> caseIds = result.caseResults().stream()
                    .map(MutantCaseResult::caseId).toList();
            if (!baselineIds.equals(caseIds)) {
                throw new IllegalArgumentException(
                        "Every mutant must retain the exact ordered oracle case closure");
            }
            for (MutantCaseResult caseResult : result.caseResults()) {
                if (!mutant.mutantTargetFingerprint().equals(
                        caseResult.mutantTargetFingerprint())
                        || !Objects.equals(baselineFixtures.get(caseResult.caseId()),
                        caseResult.fixtureBundleRef())) {
                    throw new IllegalArgumentException(
                            "Mutation child target and fixture identities must match the frozen suite");
                }
            }
        }
    }

    private static void validateLifecycle(
            TestSuiteRunEvidence.Status aggregate,
            BaselineStatus baseline,
            MutationScoreStatus score,
            TestSuiteRunEvidence.PromotionVerdict promotion,
            List<String> diagnostics) {
        if (aggregate == TestSuiteRunEvidence.Status.RUNNING) {
            if (score == MutationScoreStatus.SATISFIED
                    || score == MutationScoreStatus.UNSATISFIED) {
                throw new IllegalArgumentException(
                        "Running mutation evidence cannot carry a terminal score verdict");
            }
            return;
        }
        if (aggregate == TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE
                && (score == MutationScoreStatus.SATISFIED
                || score == MutationScoreStatus.UNSATISFIED)) {
            if (promotion.status() != TestSuiteRunEvidence.PromotionStatus.BLOCKED
                    || diagnostics.isEmpty()) {
                throw new IllegalArgumentException(
                        "Outer mutation evidence failure must block promotion and disclose a diagnostic");
            }
            return;
        }
        TestSuiteRunEvidence.Status expected;
        if (baseline == BaselineStatus.EVIDENCE_INCOMPLETE
                || score == MutationScoreStatus.INCOMPLETE
                || score == MutationScoreStatus.NOT_EVALUATED) {
            expected = TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE;
        } else if (baseline == BaselineStatus.FAILED
                || score == MutationScoreStatus.UNSATISFIED) {
            expected = TestSuiteRunEvidence.Status.COMPLETED_WITH_FAILURES;
        } else if (baseline == BaselineStatus.PASSED
                && score == MutationScoreStatus.SATISFIED) {
            expected = TestSuiteRunEvidence.Status.PASSED;
        } else {
            expected = TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE;
        }
        if (aggregate != expected) {
            throw new IllegalArgumentException(
                    "Mutation aggregate lifecycle does not match baseline and score verdicts");
        }
    }

    private static int count(List<MutantResult> values, MutantStatus status) {
        return (int) values.stream().filter(value -> value.status() == status).count();
    }

    private static List<String> immutableDiagnostics(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> safe = List.copyOf(values);
        if (safe.size() > 512 || safe.stream().anyMatch(value -> value == null
                || value.isBlank() || value.length() > 512)) {
            throw new IllegalArgumentException("Mutation diagnostics are invalid");
        }
        return safe;
    }

    private static boolean fingerprint(String value) {
        return FINGERPRINT.matcher(normalized(value)).matches();
    }

    private static String defaulted(String value, String fallback) {
        String safe = normalized(value);
        return safe.isBlank() ? fallback : safe;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
