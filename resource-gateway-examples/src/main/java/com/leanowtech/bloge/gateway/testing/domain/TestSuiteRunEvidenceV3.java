package com.leanowtech.bloge.gateway.testing.domain;

import com.leanowtech.bloge.gateway.testing.api.TestBoundaryCasePlan;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free aggregate evidence for schema-admission suite execution.
 *
 * <p>This generation proves only that every stored case still belongs to the exact reviewed
 * boundary plan and that the shared schema validator produced the expected admission outcome. It
 * never invokes the graph or operator and can never represent business promotion eligibility.</p>
 *
 * @param schemaVersion exact admission evidence generation
 * @param suiteRunId durable aggregate run id
 * @param clientRequestId caller idempotency key
 * @param status aggregate admission execution status
 * @param executionPurpose fixed schema-admission purpose
 * @param suiteRef exact immutable v3 suite revision
 * @param target exact graph or operator target
 * @param startedAt authoritative start time
 * @param completedAt terminal time or null while running
 * @param caseResults compatibility index with no child run references
 * @param coverage structural DAG coverage, always not evaluated
 * @param promotion business promotion verdict, always blocked
 * @param evaluationMode fixed schema-admission mode
 * @param boundaryPlanFingerprint exact regenerated plan fingerprint
 * @param inputSchemaFingerprint exact projected input-schema fingerprint
 * @param generatorVersion exact deterministic boundary generator generation
 * @param verificationMode exact validator proof mode
 * @param sourcePlanStatus exact regenerated plan status
 * @param sourceCoverageGapCount exact regenerated plan gap count
 * @param coverageGapsAccepted explicit acceptance required for partial plans
 * @param admissionResults ordered typed admission results
 * @param admissionCoverage admission-specific coverage verdict
 * @param diagnostics bounded stable aggregate diagnostics
 * @param metadata bounded scope provenance without case payloads
 */
public record TestSuiteRunEvidenceV3(
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
        TestSuiteV3.EvaluationMode evaluationMode,
        String boundaryPlanFingerprint,
        String inputSchemaFingerprint,
        String generatorVersion,
        String verificationMode,
        TestBoundaryCasePlan.Status sourcePlanStatus,
        int sourceCoverageGapCount,
        boolean coverageGapsAccepted,
        List<AdmissionCaseResult> admissionResults,
        AdmissionCoverageVerdict admissionCoverage,
        List<String> diagnostics,
        Map<String, Object> metadata
) implements TestSuiteRunEvidenceProtocol {
    /** Current schema-admission aggregate evidence generation. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteRunEvidence.v3";
    /** Fixed authorization purpose proving that no business target is invoked. */
    public static final String EXECUTION_PURPOSE = "SCHEMA_ADMISSION_SUITE_EXECUTION";
    /** Exact proof mode shared with graph and operator schema admission. */
    public static final String VERIFICATION_MODE = "EXACT_SHARED_VALIDATOR";
    /** Permanent promotion block reason for admission-only evidence. */
    public static final String SCHEMA_ADMISSION_ONLY = "SCHEMA_ADMISSION_ONLY";
    /** Permanent promotion block reason proving business execution was not performed. */
    public static final String BUSINESS_EXECUTION_NOT_PERFORMED =
            "BUSINESS_EXECUTION_NOT_PERFORMED";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Normalizes fields and enforces one ordered typed result per compatibility case result. */
    public TestSuiteRunEvidenceV3 {
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
                ? TestSuiteV3.EvaluationMode.SCHEMA_ADMISSION : evaluationMode;
        boundaryPlanFingerprint = normalized(boundaryPlanFingerprint);
        inputSchemaFingerprint = normalized(inputSchemaFingerprint);
        generatorVersion = normalized(generatorVersion);
        verificationMode = normalized(verificationMode);
        admissionResults = admissionResults == null ? List.of() : List.copyOf(admissionResults);
        admissionCoverage = admissionCoverage == null
                ? AdmissionCoverageVerdict.notEvaluated(admissionResults.size()) : admissionCoverage;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        boolean exactCaseClosure = caseResults.size() == admissionResults.size();
        for (int index = 0; exactCaseClosure && index < caseResults.size(); index++) {
            exactCaseClosure = caseResults.get(index).caseId()
                    .equals(admissionResults.get(index).caseId());
        }
        boolean planShape = sourcePlanStatus == TestBoundaryCasePlan.Status.GENERATED
                ? sourceCoverageGapCount == 0 && !coverageGapsAccepted
                : sourcePlanStatus == TestBoundaryCasePlan.Status.PARTIAL
                && sourceCoverageGapCount > 0 && coverageGapsAccepted;
        if (!SCHEMA_VERSION.equals(schemaVersion) || suiteRef == null || target == null
                || startedAt == null || caseResults.isEmpty() || generatorVersion.isBlank()
                || !EXECUTION_PURPOSE.equals(executionPurpose)
                || !VERIFICATION_MODE.equals(verificationMode)
                || evaluationMode != TestSuiteV3.EvaluationMode.SCHEMA_ADMISSION
                || sourceCoverageGapCount < 0 || !planShape || !exactCaseClosure
                || !FINGERPRINT.matcher(boundaryPlanFingerprint).matches()
                || !FINGERPRINT.matcher(inputSchemaFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "Schema-admission evidence requires exact plan, schema, and ordered case closure");
        }
        if ((status == TestSuiteRunEvidence.Status.RUNNING) != (completedAt == null)) {
            throw new IllegalArgumentException(
                    "Schema-admission evidence completion time must match aggregate lifecycle");
        }
        if (!TestSuiteRunEvidence.CoverageVerdict.notEvaluated().equals(coverage)) {
            throw new IllegalArgumentException(
                    "Schema-admission evidence cannot claim business structural coverage");
        }
        if (!promotionBlocked(promotion, admissionCoverage)) {
            throw new IllegalArgumentException(
                    "Schema-admission evidence must remain blocked from business promotion");
        }
        if (caseResults.stream().anyMatch(result -> !result.runId().isBlank()
                || result.evidenceStatus() != null || result.evidenceClass() != null
                || result.assertionsEvaluated() != 0 || result.assertionsPassed() != 0)) {
            throw new IllegalArgumentException(
                    "Schema-admission evidence cannot reference business child execution");
        }
        if (!terminalCoverageMatches(status, admissionCoverage)) {
            throw new IllegalArgumentException(
                    "Schema-admission aggregate status and admission coverage must agree");
        }
    }

    /** Typed lifecycle for one schema-admission result. */
    public enum AdmissionCaseStatus {
        /** The case has not reached validator evaluation. */
        PENDING,
        /** Proven plan provenance and observed validator result both match the suite. */
        MATCHED,
        /** Validator outcome or diagnostics differ from the suite expectation. */
        EXPECTATION_MISMATCH,
        /** Stored case input or expectation is not the exact selected plan case. */
        PROVENANCE_MISMATCH,
        /** Evaluation could not produce independently usable evidence. */
        EVIDENCE_INCOMPLETE,
        /** Scheduling stopped under fail-fast or lease loss. */
        NOT_SCHEDULED
    }

    /** Admission-specific aggregate coverage state. */
    public enum AdmissionCoverageStatus {
        NOT_EVALUATED,
        SATISFIED,
        UNSATISFIED,
        INCOMPLETE
    }

    /**
     * One payload-free admission observation.
     *
     * @param caseId suite-local case id
     * @param status typed admission status
     * @param expectedOutcome suite-bound expected outcome
     * @param observedOutcome validator result; null before or without evaluation
     * @param expectedValidationCodes exact expected diagnostic codes
     * @param observedValidationCodes exact observed diagnostic codes
     * @param diagnosticCode bounded stable mismatch or failure code
     */
    public record AdmissionCaseResult(
            String caseId,
            AdmissionCaseStatus status,
            TestSuiteV3.ExpectedOutcome expectedOutcome,
            TestSuiteV3.ExpectedOutcome observedOutcome,
            List<String> expectedValidationCodes,
            List<String> observedValidationCodes,
            String diagnosticCode
    ) {
        /** Canonicalizes code sets and validates terminal result consistency. */
        public AdmissionCaseResult {
            caseId = normalized(caseId);
            status = Objects.requireNonNull(status, "status");
            expectedOutcome = Objects.requireNonNull(expectedOutcome, "expectedOutcome");
            expectedValidationCodes = sortedCodes(expectedValidationCodes);
            observedValidationCodes = sortedCodes(observedValidationCodes);
            diagnosticCode = normalized(diagnosticCode);
            if (caseId.isBlank()) {
                throw new IllegalArgumentException("Admission result requires a caseId");
            }
            if (status == AdmissionCaseStatus.MATCHED
                    && (observedOutcome != expectedOutcome
                    || !observedValidationCodes.equals(expectedValidationCodes)
                    || !diagnosticCode.isBlank())) {
                throw new IllegalArgumentException("Matched admission result must exactly match");
            }
            if ((status == AdmissionCaseStatus.EXPECTATION_MISMATCH
                    || status == AdmissionCaseStatus.PROVENANCE_MISMATCH)
                    && (observedOutcome == null || diagnosticCode.isBlank())) {
                throw new IllegalArgumentException(
                        "Admission mismatch requires an observed outcome and diagnostic code");
            }
        }
    }

    /**
     * Admission-specific aggregate coverage derived from typed case results.
     *
     * @param status aggregate coverage status
     * @param requiredCases exact suite case count
     * @param evaluatedCases cases that reached the validator
     * @param matchedCases exact expectation matches
     * @param expectationMismatchCaseIds cases with validator expectation drift
     * @param provenanceMismatchCaseIds cases outside the exact reviewed plan
     * @param incompleteCaseIds cases without complete evidence
     * @param allCasesCompleted whether no pending or unscheduled case remains
     */
    public record AdmissionCoverageVerdict(
            AdmissionCoverageStatus status,
            int requiredCases,
            int evaluatedCases,
            int matchedCases,
            List<String> expectationMismatchCaseIds,
            List<String> provenanceMismatchCaseIds,
            List<String> incompleteCaseIds,
            boolean allCasesCompleted
    ) {
        /** Canonicalizes identifiers and validates aggregate counters. */
        public AdmissionCoverageVerdict {
            status = status == null ? AdmissionCoverageStatus.NOT_EVALUATED : status;
            expectationMismatchCaseIds = sortedCodes(expectationMismatchCaseIds);
            provenanceMismatchCaseIds = sortedCodes(provenanceMismatchCaseIds);
            incompleteCaseIds = sortedCodes(incompleteCaseIds);
            if (requiredCases < 0 || evaluatedCases < 0 || matchedCases < 0
                    || evaluatedCases > requiredCases || matchedCases > evaluatedCases) {
                throw new IllegalArgumentException("Admission coverage counters are inconsistent");
            }
        }

        /** @return pending verdict for a newly signed checkpoint */
        public static AdmissionCoverageVerdict notEvaluated(int requiredCases) {
            return new AdmissionCoverageVerdict(AdmissionCoverageStatus.NOT_EVALUATED,
                    requiredCases, 0, 0, List.of(), List.of(), List.of(), false);
        }
    }

    private static List<String> sortedCodes(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> normalizedValues = values.stream()
                .map(TestSuiteRunEvidenceV3::normalized).toList();
        if (normalizedValues.stream().anyMatch(String::isBlank)
                || new LinkedHashSet<>(normalizedValues).size() != normalizedValues.size()) {
            throw new IllegalArgumentException(
                    "Admission evidence identifiers must be unique and non-empty");
        }
        List<String> sorted = new ArrayList<>(normalizedValues);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    private static boolean promotionBlocked(
            TestSuiteRunEvidence.PromotionVerdict promotion,
            AdmissionCoverageVerdict admissionCoverage) {
        return promotion.status() == TestSuiteRunEvidence.PromotionStatus.BLOCKED
                && promotion.reasons().contains(SCHEMA_ADMISSION_ONLY)
                && promotion.reasons().contains(BUSINESS_EXECUTION_NOT_PERFORMED)
                && promotion.certifiableCases() == 0
                && promotion.minimumCertifiableCases() == 0
                && !promotion.targetCertificationEligible()
                && !promotion.coverageSatisfied()
                && promotion.allCasesCompleted() == admissionCoverage.allCasesCompleted();
    }

    private static boolean terminalCoverageMatches(
            TestSuiteRunEvidence.Status status, AdmissionCoverageVerdict admissionCoverage) {
        return switch (status) {
            case RUNNING -> admissionCoverage.status() == AdmissionCoverageStatus.NOT_EVALUATED
                    || admissionCoverage.status() == AdmissionCoverageStatus.INCOMPLETE;
            case PASSED -> admissionCoverage.status() == AdmissionCoverageStatus.SATISFIED
                    && admissionCoverage.allCasesCompleted();
            case COMPLETED_WITH_FAILURES ->
                    admissionCoverage.status() == AdmissionCoverageStatus.UNSATISFIED
                            && admissionCoverage.allCasesCompleted();
            case PARTIAL, EVIDENCE_INCOMPLETE ->
                    admissionCoverage.status() == AdmissionCoverageStatus.INCOMPLETE;
        };
    }

    private static String defaulted(String value, String fallback) {
        String safe = normalized(value);
        return safe.isBlank() ? fallback : safe;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
