package com.leanowtech.bloge.gateway.testing.domain;

import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free aggregate evidence for one immutable bounded-property suite execution.
 *
 * <p>V4 distinguishes an assertion counterexample from target or evidence failure. It binds every
 * root and precomputed shrink candidate to its canonical input fingerprint and signed child run,
 * and reports only the smallest counterexample observed inside each frozen shrink path. It never
 * claims exhaustive input-space coverage or global counterexample minimality.</p>
 *
 * @param schemaVersion exact property evidence generation
 * @param suiteRunId durable aggregate run id
 * @param clientRequestId caller idempotency key
 * @param status aggregate execution status
 * @param executionPurpose fixed property-suite purpose
 * @param suiteRef exact immutable V4 suite revision
 * @param target exact graph or operator target
 * @param startedAt authoritative start time
 * @param completedAt terminal time or null while running
 * @param caseResults ordered compatibility child results
 * @param coverage structural child-evidence coverage
 * @param promotion server-derived promotion eligibility
 * @param evaluationMode fixed property execution mode
 * @param quantification fixed bounded-sampled quantifier
 * @param exhaustive fixed false
 * @param propertyPlanFingerprint exact reviewed property plan
 * @param inputSchemaFingerprint exact projected input schema
 * @param generationPolicy exact seed and bounded generation policy
 * @param sourcePlanStatus generated or explicitly accepted partial plan
 * @param generationGapsAccepted whether partial generation gaps were accepted
 * @param generationGaps stable source-plan gaps
 * @param propertyTrialResults ordered root and shrink execution closure
 * @param propertyCoverage property-specific aggregate verdict
 * @param diagnostics bounded stable aggregate diagnostics
 * @param metadata bounded scope provenance without case payloads
 */
public record TestSuiteRunEvidenceV4(
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
        TestSuiteV4.EvaluationMode evaluationMode,
        TestSuiteV4.Quantification quantification,
        boolean exhaustive,
        String propertyPlanFingerprint,
        String inputSchemaFingerprint,
        TestSuiteV4.PropertyGenerationPolicy generationPolicy,
        TestSuiteV4.SourcePlanStatus sourcePlanStatus,
        boolean generationGapsAccepted,
        List<TestSuiteV4.PropertyGenerationGap> generationGaps,
        List<PropertyTrialResult> propertyTrialResults,
        PropertyCoverageVerdict propertyCoverage,
        List<String> diagnostics,
        Map<String, Object> metadata
) implements TestSuiteRunEvidenceProtocol {
    /** Current bounded-property aggregate evidence generation. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteRunEvidence.v4";
    /** Fixed authorization purpose for V4 property execution. */
    public static final String EXECUTION_PURPOSE = "PROPERTY_SUITE_EXECUTION";
    /** Honest minimality scope attached to every observed counterexample. */
    public static final String MINIMALITY_SCOPE = "PRECOMPUTED_SHRINK_PATH";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern MACHINE_CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    /** Normalizes protocol values and validates the complete common/property result closure. */
    public TestSuiteRunEvidenceV4 {
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
                ? TestSuiteV4.EvaluationMode.PROPERTY_EXECUTION : evaluationMode;
        quantification = quantification == null
                ? TestSuiteV4.Quantification.BOUNDED_SAMPLED : quantification;
        propertyPlanFingerprint = normalized(propertyPlanFingerprint);
        inputSchemaFingerprint = normalized(inputSchemaFingerprint);
        generationPolicy = Objects.requireNonNull(generationPolicy, "generationPolicy");
        sourcePlanStatus = Objects.requireNonNull(sourcePlanStatus, "sourcePlanStatus");
        generationGaps = generationGaps == null ? List.of() : List.copyOf(generationGaps);
        propertyTrialResults = propertyTrialResults == null
                ? List.of() : List.copyOf(propertyTrialResults);
        propertyCoverage = Objects.requireNonNull(propertyCoverage, "propertyCoverage");
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        metadata = ProtocolJsonValue.freezeMap(metadata);

        boolean planShape = sourcePlanStatus == TestSuiteV4.SourcePlanStatus.GENERATED
                ? generationGaps.isEmpty() && !generationGapsAccepted
                && propertyTrialResults.size() == generationPolicy.requestedTrials()
                : !generationGaps.isEmpty() && generationGapsAccepted;
        if (!SCHEMA_VERSION.equals(schemaVersion) || suiteRunId.isBlank()
                || clientRequestId.isBlank() || suiteRef == null || target == null
                || startedAt == null || caseResults.isEmpty() || propertyTrialResults.isEmpty()
                || !EXECUTION_PURPOSE.equals(executionPurpose)
                || evaluationMode != TestSuiteV4.EvaluationMode.PROPERTY_EXECUTION
                || quantification != TestSuiteV4.Quantification.BOUNDED_SAMPLED || exhaustive
                || !FINGERPRINT.matcher(propertyPlanFingerprint).matches()
                || !FINGERPRINT.matcher(inputSchemaFingerprint).matches() || !planShape) {
            throw new IllegalArgumentException(
                    "Property evidence requires exact non-exhaustive plan and execution identity");
        }
        if ((status == TestSuiteRunEvidence.Status.RUNNING) != (completedAt == null)) {
            throw new IllegalArgumentException(
                    "Property evidence completion time must match aggregate lifecycle");
        }
        validateClosure(caseResults, propertyTrialResults, generationPolicy);
        boolean incompleteAggregate = status == TestSuiteRunEvidence.Status.PARTIAL
                || status == TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE;
        if (!propertyCoverage.matches(propertyTrialResults, incompleteAggregate)) {
            throw new IllegalArgumentException(
                    "Property coverage must be derived from the complete typed trial closure");
        }
        if (propertyCoverage.status() == PropertyCoverageStatus.INCOMPLETE
                && deriveCoverageStatus(propertyTrialResults.stream()
                .flatMap(trial -> closure(trial.rootResult(), trial.shrinkResults()).stream())
                .toList()) != PropertyCoverageStatus.INCOMPLETE
                && diagnostics.isEmpty()) {
            throw new IllegalArgumentException(
                    "Fail-closed property coverage requires an aggregate diagnostic");
        }
        if (!lifecycleMatches(status, propertyCoverage)) {
            throw new IllegalArgumentException(
                    "Property aggregate status and property coverage are inconsistent");
        }
    }

    /** Position of one generated input inside a property trial. */
    public enum PropertyCaseRole {
        /** Root sample generated directly from the seeded policy. */
        ROOT,
        /** Strictly simpler candidate in the root's precomputed shrink path. */
        SHRINK
    }

    /** Typed interpretation of one signed child result. */
    public enum PropertyCaseStatus {
        /** Child scheduling has not started. */
        PENDING,
        /** Every governed assertion passed for this generated input. */
        SATISFIED,
        /** Complete child evidence ended specifically in assertion failure. */
        COUNTEREXAMPLE,
        /** Target/control execution failed and therefore cannot prove a property violation. */
        EXECUTION_FAILED,
        /** Signed evidence is absent, invalid, or incomplete. */
        EVIDENCE_INCOMPLETE,
        /** Fail-fast or lease loss prevented scheduling. */
        NOT_SCHEDULED
    }

    /** Aggregate state for one root and its frozen shrink path. */
    public enum PropertyTrialStatus {
        SATISFIED,
        COUNTEREXAMPLE,
        EXECUTION_FAILED,
        INCOMPLETE
    }

    /** Property-specific aggregate evaluation state. */
    public enum PropertyCoverageStatus {
        NOT_EVALUATED,
        SATISFIED,
        COUNTEREXAMPLE,
        EXECUTION_FAILED,
        INCOMPLETE
    }

    /**
     * Typed payload-free result for one generated root or shrink input.
     *
     * @param caseId suite-local generated case id
     * @param role root or shrink role
     * @param parentCaseId previous case in the shrink chain; blank for a root
     * @param shrinkStep zero for a root, otherwise one-based shrink step
     * @param inputFingerprint canonical generated input fingerprint
     * @param complexity deterministic simplification score
     * @param status property-specific interpretation
     * @param runId signed child run id when available
     * @param evidenceStatus exact child evidence status when available
     * @param assertionsEvaluated governed assertion count
     * @param assertionsPassed passing assertion count
     * @param diagnosticCode stable payload-free failure code
     */
    public record PropertyCaseResult(
            String caseId,
            PropertyCaseRole role,
            String parentCaseId,
            int shrinkStep,
            String inputFingerprint,
            int complexity,
            PropertyCaseStatus status,
            String runId,
            TestRunEvidence.Status evidenceStatus,
            int assertionsEvaluated,
            int assertionsPassed,
            String diagnosticCode
    ) {
        /** Normalizes values and rejects false counterexample or success claims. */
        public PropertyCaseResult {
            caseId = normalized(caseId);
            role = Objects.requireNonNull(role, "role");
            parentCaseId = normalized(parentCaseId);
            inputFingerprint = normalized(inputFingerprint);
            status = Objects.requireNonNull(status, "status");
            runId = normalized(runId);
            diagnosticCode = normalized(diagnosticCode);
            boolean rootShape = role == PropertyCaseRole.ROOT
                    && parentCaseId.isBlank() && shrinkStep == 0;
            boolean shrinkShape = role == PropertyCaseRole.SHRINK
                    && !parentCaseId.isBlank() && shrinkStep >= 1 && shrinkStep <= 5;
            if (caseId.isBlank() || (!rootShape && !shrinkShape)
                    || !FINGERPRINT.matcher(inputFingerprint).matches() || complexity < 0
                    || assertionsEvaluated < 0 || assertionsPassed < 0
                    || assertionsPassed > assertionsEvaluated
                    || !diagnosticCode.isBlank() && !MACHINE_CODE.matcher(diagnosticCode).matches()) {
                throw new IllegalArgumentException("Property case result is malformed");
            }
            boolean childIdentity = !runId.isBlank() && evidenceStatus != null;
            boolean noChild = runId.isBlank() && evidenceStatus == null;
            if (!childIdentity && !noChild) {
                throw new IllegalArgumentException("Property child identity is incomplete");
            }
            switch (status) {
                case SATISFIED -> {
                    if (!childIdentity || evidenceStatus != TestRunEvidence.Status.PASSED
                            || assertionsEvaluated < 1 || assertionsPassed != assertionsEvaluated
                            || !diagnosticCode.isBlank()) {
                        throw new IllegalArgumentException(
                                "Satisfied property case requires passing assertion evidence");
                    }
                }
                case COUNTEREXAMPLE -> {
                    if (!childIdentity || evidenceStatus != TestRunEvidence.Status.ASSERTION_FAILED
                            || assertionsEvaluated < 1 || assertionsPassed >= assertionsEvaluated) {
                        throw new IllegalArgumentException(
                                "Only assertion failure may be classified as a counterexample");
                    }
                }
                case EXECUTION_FAILED -> {
                    if (!childIdentity || List.of(TestRunEvidence.Status.PASSED,
                            TestRunEvidence.Status.ASSERTION_FAILED,
                            TestRunEvidence.Status.EVIDENCE_INCOMPLETE).contains(evidenceStatus)) {
                        throw new IllegalArgumentException(
                                "Execution failure cannot be a passing or assertion-only result");
                    }
                }
                case PENDING, NOT_SCHEDULED -> {
                    if (!noChild || assertionsEvaluated != 0 || assertionsPassed != 0) {
                        throw new IllegalArgumentException(
                                "Unscheduled property cases cannot claim child evidence");
                    }
                }
                case EVIDENCE_INCOMPLETE -> {
                    if (childIdentity && evidenceStatus != TestRunEvidence.Status.EVIDENCE_INCOMPLETE) {
                        throw new IllegalArgumentException(
                                "Incomplete property evidence cannot retain another terminal status");
                    }
                }
            }
        }
    }

    /**
     * Smallest counterexample observed inside one frozen shrink path.
     *
     * @param caseId generated counterexample case id
     * @param inputFingerprint canonical input fingerprint; no input payload is copied
     * @param complexity deterministic simplification score
     * @param minimalityScope fixed precomputed-path scope
     * @param globallyMinimal fixed false
     */
    public record CounterexampleRef(
            String caseId,
            String inputFingerprint,
            int complexity,
            String minimalityScope,
            boolean globallyMinimal
    ) {
        /** Enforces the protocol's deliberately narrow minimality claim. */
        public CounterexampleRef {
            caseId = normalized(caseId);
            inputFingerprint = normalized(inputFingerprint);
            minimalityScope = normalized(minimalityScope);
            if (caseId.isBlank() || !FINGERPRINT.matcher(inputFingerprint).matches()
                    || complexity < 0 || !MINIMALITY_SCOPE.equals(minimalityScope)
                    || globallyMinimal) {
                throw new IllegalArgumentException("Counterexample minimality claim is invalid");
            }
        }
    }

    /**
     * One root and its complete ordered shrink execution result.
     *
     * @param trialId root trial id
     * @param status derived trial result
     * @param rootResult root case result
     * @param shrinkResults complete ordered shrink path
     * @param minimalObservedCounterexample smallest failing case within this path, or null
     */
    public record PropertyTrialResult(
            String trialId,
            PropertyTrialStatus status,
            PropertyCaseResult rootResult,
            List<PropertyCaseResult> shrinkResults,
            CounterexampleRef minimalObservedCounterexample
    ) {
        /** Freezes and verifies parent, complexity, status, and minimal-counterexample closure. */
        public PropertyTrialResult {
            trialId = normalized(trialId);
            status = Objects.requireNonNull(status, "status");
            rootResult = Objects.requireNonNull(rootResult, "rootResult");
            shrinkResults = shrinkResults == null ? List.of() : List.copyOf(shrinkResults);
            if (!trialId.equals(rootResult.caseId())
                    || rootResult.role() != PropertyCaseRole.ROOT || shrinkResults.size() > 5) {
                throw new IllegalArgumentException("Property trial root is inconsistent");
            }
            String parent = trialId;
            int complexity = rootResult.complexity();
            for (int index = 0; index < shrinkResults.size(); index++) {
                PropertyCaseResult shrink = Objects.requireNonNull(
                        shrinkResults.get(index), "shrinkResult");
                if (shrink.role() != PropertyCaseRole.SHRINK
                        || !parent.equals(shrink.parentCaseId())
                        || shrink.shrinkStep() != index + 1 || shrink.complexity() >= complexity) {
                    throw new IllegalArgumentException(
                            "Property shrink results must form a strictly simpler linear path");
                }
                parent = shrink.caseId();
                complexity = shrink.complexity();
            }
            List<PropertyCaseResult> closure = closure(rootResult, shrinkResults);
            if (status != deriveTrialStatus(closure)
                    || !Objects.equals(minimalObservedCounterexample,
                    deriveMinimalCounterexample(closure))) {
                throw new IllegalArgumentException(
                        "Property trial status and minimal counterexample must be derived");
            }
        }
    }

    /**
     * Aggregate property verdict derived from every frozen root and shrink case.
     *
     * @param status property evaluation state
     * @param requiredTrials exact root count
     * @param completedTrials roots without pending/incomplete children
     * @param requiredCases exact root-plus-shrink case count
     * @param evaluatedCases cases with a determinate property or execution result
     * @param satisfiedCases generated inputs whose assertions passed
     * @param counterexampleCases generated inputs whose assertions failed
     * @param executionFailedCaseIds target/control failures, never counterexamples
     * @param incompleteCaseIds cases without complete signed evidence
     * @param minimalObservedCounterexamples one path-scoped minimum per failing trial
     * @param allCasesCompleted whether every generated case has a determinate signed result
     * @param minimalityScope fixed precomputed-path scope
     * @param globallyMinimal fixed false
     */
    public record PropertyCoverageVerdict(
            PropertyCoverageStatus status,
            int requiredTrials,
            int completedTrials,
            int requiredCases,
            int evaluatedCases,
            int satisfiedCases,
            int counterexampleCases,
            List<String> executionFailedCaseIds,
            List<String> incompleteCaseIds,
            List<CounterexampleRef> minimalObservedCounterexamples,
            boolean allCasesCompleted,
            String minimalityScope,
            boolean globallyMinimal
    ) {
        /** Normalizes and bounds property counters without trusting author-supplied coverage. */
        public PropertyCoverageVerdict {
            status = Objects.requireNonNull(status, "status");
            executionFailedCaseIds = unique(executionFailedCaseIds);
            incompleteCaseIds = unique(incompleteCaseIds);
            minimalObservedCounterexamples = minimalObservedCounterexamples == null
                    ? List.of() : List.copyOf(minimalObservedCounterexamples);
            minimalityScope = normalized(minimalityScope);
            if (requiredTrials < 1 || requiredTrials > 16 || completedTrials < 0
                    || completedTrials > requiredTrials || requiredCases < 1 || requiredCases > 96
                    || evaluatedCases < 0 || evaluatedCases > requiredCases || satisfiedCases < 0
                    || counterexampleCases < 0
                    || satisfiedCases + counterexampleCases > evaluatedCases
                    || !MINIMALITY_SCOPE.equals(minimalityScope) || globallyMinimal) {
                throw new IllegalArgumentException("Property coverage counters are inconsistent");
            }
        }

        private boolean matches(List<PropertyTrialResult> trials,
                                boolean allowIncompleteAggregate) {
            List<PropertyCaseResult> cases = trials.stream()
                    .flatMap(trial -> closure(trial.rootResult(), trial.shrinkResults()).stream())
                    .toList();
            List<String> executionFailures = cases.stream()
                    .filter(value -> value.status() == PropertyCaseStatus.EXECUTION_FAILED)
                    .map(PropertyCaseResult::caseId).toList();
            List<String> incomplete = cases.stream()
                    .filter(value -> List.of(PropertyCaseStatus.PENDING,
                            PropertyCaseStatus.NOT_SCHEDULED,
                            PropertyCaseStatus.EVIDENCE_INCOMPLETE).contains(value.status()))
                    .map(PropertyCaseResult::caseId).toList();
            List<CounterexampleRef> minima = trials.stream()
                    .map(PropertyTrialResult::minimalObservedCounterexample)
                    .filter(Objects::nonNull).toList();
            PropertyCoverageStatus derivedStatus = deriveCoverageStatus(cases);
            boolean statusMatches = status == derivedStatus
                    || allowIncompleteAggregate && status == PropertyCoverageStatus.INCOMPLETE;
            return statusMatches
                    && requiredTrials == trials.size()
                    && completedTrials == trials.stream().filter(trial ->
                    trial.status() != PropertyTrialStatus.INCOMPLETE).count()
                    && requiredCases == cases.size()
                    && evaluatedCases == cases.stream().filter(value -> List.of(
                    PropertyCaseStatus.SATISFIED, PropertyCaseStatus.COUNTEREXAMPLE,
                    PropertyCaseStatus.EXECUTION_FAILED).contains(value.status())).count()
                    && satisfiedCases == cases.stream().filter(value ->
                    value.status() == PropertyCaseStatus.SATISFIED).count()
                    && counterexampleCases == cases.stream().filter(value ->
                    value.status() == PropertyCaseStatus.COUNTEREXAMPLE).count()
                    && executionFailedCaseIds.equals(executionFailures)
                    && incompleteCaseIds.equals(incomplete)
                    && minimalObservedCounterexamples.equals(minima)
                    && allCasesCompleted == incomplete.isEmpty();
        }
    }

    /** Derives one immutable path result from already validated case results. */
    public static PropertyTrialResult trialResult(
            String trialId, PropertyCaseResult root, List<PropertyCaseResult> shrinks) {
        List<PropertyCaseResult> closure = closure(root, shrinks);
        return new PropertyTrialResult(trialId, deriveTrialStatus(closure), root, shrinks,
                deriveMinimalCounterexample(closure));
    }

    /** Derives aggregate property coverage without accepting author-owned counters. */
    public static PropertyCoverageVerdict coverage(List<PropertyTrialResult> trials) {
        return coverage(trials, false);
    }

    /**
     * Derives exact counters while fail-closing the aggregate verdict itself.
     *
     * <p>This is reserved for failures above the child closure, such as aggregate signature or
     * terminal persistence failure. Completed child facts remain visible, but the aggregate may
     * not claim a trustworthy property verdict.</p>
     *
     * @param trials exact typed child closure
     * @return coverage with exact counters and an incomplete aggregate status
     */
    public static PropertyCoverageVerdict incompleteCoverage(
            List<PropertyTrialResult> trials) {
        return coverage(trials, true);
    }

    private static PropertyCoverageVerdict coverage(
            List<PropertyTrialResult> trials, boolean aggregateIncomplete) {
        List<PropertyTrialResult> safe = trials == null ? List.of() : List.copyOf(trials);
        List<PropertyCaseResult> cases = safe.stream()
                .flatMap(trial -> closure(trial.rootResult(), trial.shrinkResults()).stream())
                .toList();
        List<String> executionFailures = cases.stream()
                .filter(value -> value.status() == PropertyCaseStatus.EXECUTION_FAILED)
                .map(PropertyCaseResult::caseId).toList();
        List<String> incomplete = cases.stream()
                .filter(value -> List.of(PropertyCaseStatus.PENDING,
                        PropertyCaseStatus.NOT_SCHEDULED,
                        PropertyCaseStatus.EVIDENCE_INCOMPLETE).contains(value.status()))
                .map(PropertyCaseResult::caseId).toList();
        return new PropertyCoverageVerdict(aggregateIncomplete
                ? PropertyCoverageStatus.INCOMPLETE : deriveCoverageStatus(cases), safe.size(),
                (int) safe.stream().filter(trial ->
                        trial.status() != PropertyTrialStatus.INCOMPLETE).count(), cases.size(),
                (int) cases.stream().filter(value -> List.of(PropertyCaseStatus.SATISFIED,
                        PropertyCaseStatus.COUNTEREXAMPLE,
                        PropertyCaseStatus.EXECUTION_FAILED).contains(value.status())).count(),
                (int) cases.stream().filter(value ->
                        value.status() == PropertyCaseStatus.SATISFIED).count(),
                (int) cases.stream().filter(value ->
                        value.status() == PropertyCaseStatus.COUNTEREXAMPLE).count(),
                executionFailures, incomplete, safe.stream()
                .map(PropertyTrialResult::minimalObservedCounterexample)
                .filter(Objects::nonNull).toList(), incomplete.isEmpty(), MINIMALITY_SCOPE, false);
    }

    private static void validateClosure(
            List<TestSuiteRunEvidence.CaseResult> common,
            List<PropertyTrialResult> trials,
            TestSuiteV4.PropertyGenerationPolicy policy) {
        List<PropertyCaseResult> property = trials.stream()
                .flatMap(trial -> closure(trial.rootResult(), trial.shrinkResults()).stream())
                .toList();
        if (trials.size() > policy.requestedTrials() || property.size() > policy.maxCases()
                || common.size() != property.size()) {
            throw new IllegalArgumentException("Property evidence exceeds its frozen policy");
        }
        for (int index = 0; index < common.size(); index++) {
            TestSuiteRunEvidence.CaseResult base = common.get(index);
            PropertyCaseResult typed = property.get(index);
            boolean commonStatus = switch (typed.status()) {
                case PENDING -> base.status() == TestSuiteRunEvidence.CaseStatus.PENDING;
                case SATISFIED -> base.status() == TestSuiteRunEvidence.CaseStatus.PASSED;
                case COUNTEREXAMPLE, EXECUTION_FAILED ->
                        base.status() == TestSuiteRunEvidence.CaseStatus.FAILED;
                case EVIDENCE_INCOMPLETE ->
                        base.status() == TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE;
                case NOT_SCHEDULED ->
                        base.status() == TestSuiteRunEvidence.CaseStatus.NOT_SCHEDULED;
            };
            if (!base.caseId().equals(typed.caseId())
                    || base.caseType() != TestSuite.CaseType.PROPERTY || !commonStatus
                    || !base.runId().equals(typed.runId())
                    || base.evidenceStatus() != typed.evidenceStatus()
                    || base.assertionsEvaluated() != typed.assertionsEvaluated()
                    || base.assertionsPassed() != typed.assertionsPassed()) {
                throw new IllegalArgumentException(
                        "Property and compatibility case results must describe one child closure");
            }
        }
    }

    private static PropertyTrialStatus deriveTrialStatus(List<PropertyCaseResult> cases) {
        if (cases.stream().anyMatch(value -> List.of(PropertyCaseStatus.PENDING,
                PropertyCaseStatus.NOT_SCHEDULED,
                PropertyCaseStatus.EVIDENCE_INCOMPLETE).contains(value.status()))) {
            return PropertyTrialStatus.INCOMPLETE;
        }
        if (cases.stream().anyMatch(value ->
                value.status() == PropertyCaseStatus.EXECUTION_FAILED)) {
            return PropertyTrialStatus.EXECUTION_FAILED;
        }
        return cases.stream().anyMatch(value ->
                value.status() == PropertyCaseStatus.COUNTEREXAMPLE)
                ? PropertyTrialStatus.COUNTEREXAMPLE : PropertyTrialStatus.SATISFIED;
    }

    private static PropertyCoverageStatus deriveCoverageStatus(List<PropertyCaseResult> cases) {
        if (!cases.isEmpty() && cases.stream().allMatch(value ->
                value.status() == PropertyCaseStatus.PENDING)) {
            return PropertyCoverageStatus.NOT_EVALUATED;
        }
        if (cases.stream().anyMatch(value -> List.of(PropertyCaseStatus.PENDING,
                PropertyCaseStatus.NOT_SCHEDULED,
                PropertyCaseStatus.EVIDENCE_INCOMPLETE).contains(value.status()))) {
            return PropertyCoverageStatus.INCOMPLETE;
        }
        if (cases.stream().anyMatch(value ->
                value.status() == PropertyCaseStatus.EXECUTION_FAILED)) {
            return PropertyCoverageStatus.EXECUTION_FAILED;
        }
        return cases.stream().anyMatch(value ->
                value.status() == PropertyCaseStatus.COUNTEREXAMPLE)
                ? PropertyCoverageStatus.COUNTEREXAMPLE : PropertyCoverageStatus.SATISFIED;
    }

    private static CounterexampleRef deriveMinimalCounterexample(List<PropertyCaseResult> cases) {
        PropertyCaseResult minimum = null;
        for (PropertyCaseResult value : cases) {
            if (value.status() == PropertyCaseStatus.COUNTEREXAMPLE
                    && (minimum == null || value.complexity() < minimum.complexity())) {
                minimum = value;
            }
        }
        return minimum == null ? null : new CounterexampleRef(minimum.caseId(),
                minimum.inputFingerprint(), minimum.complexity(), MINIMALITY_SCOPE, false);
    }

    private static boolean lifecycleMatches(
            TestSuiteRunEvidence.Status status, PropertyCoverageVerdict coverage) {
        return switch (status) {
            // A signed checkpoint is still RUNNING after the final child completes and before the
            // terminal aggregate is persisted, so any derived child-closure verdict is valid here.
            case RUNNING -> true;
            case PASSED -> coverage.status() == PropertyCoverageStatus.SATISFIED
                    && coverage.allCasesCompleted();
            case COMPLETED_WITH_FAILURES -> List.of(PropertyCoverageStatus.COUNTEREXAMPLE,
                    PropertyCoverageStatus.EXECUTION_FAILED).contains(coverage.status())
                    && coverage.allCasesCompleted();
            case PARTIAL, EVIDENCE_INCOMPLETE ->
                    coverage.status() == PropertyCoverageStatus.INCOMPLETE;
        };
    }

    private static List<PropertyCaseResult> closure(
            PropertyCaseResult root, List<PropertyCaseResult> shrinks) {
        List<PropertyCaseResult> values = new ArrayList<>();
        values.add(Objects.requireNonNull(root, "root"));
        if (shrinks != null) {
            values.addAll(shrinks);
        }
        return List.copyOf(values);
    }

    private static List<String> unique(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> safe = values.stream().map(TestSuiteRunEvidenceV4::normalized).toList();
        if (safe.stream().anyMatch(String::isBlank)
                || new LinkedHashSet<>(safe).size() != safe.size()) {
            throw new IllegalArgumentException(
                    "Property evidence identifiers must be unique and non-empty");
        }
        return List.copyOf(safe);
    }

    private static String defaulted(String value, String fallback) {
        String safe = normalized(value);
        return safe.isBlank() ? fallback : safe;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
