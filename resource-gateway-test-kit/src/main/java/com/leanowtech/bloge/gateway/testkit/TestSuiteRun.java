package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * CI-oriented, payload-free projection of one immutable governed suite run.
 *
 * <p>Case projections contain only immutable identities, statuses, counters, and stable diagnostic
 * codes. Free-form diagnostics and child payloads are deliberately excluded from all built-in
 * assertions and reporters.</p>
 *
 * @param suiteRunId durable aggregate run id
 * @param clientRequestId caller-owned idempotency key
 * @param status aggregate suite status
 * @param suiteId exact suite id
 * @param suiteRevision exact suite revision
 * @param suiteFingerprint exact suite fingerprint
 * @param targetKind graph or operator
 * @param targetId registered target id
 * @param targetFingerprint frozen target fingerprint
 * @param evidenceFingerprint canonical aggregate evidence fingerprint
 * @param caseResults ordered child-run links and outcomes
 * @param coverageStatus aggregate structural coverage verdict
 * @param promotionStatus policy eligibility verdict, not a certification or publication
 * @param promotionReasons stable policy reason codes
 * @param attestation signed checkpoint or terminal aggregate closure; unsigned for v1 responses
 * @param rawResponse defensive complete response for explicit authorized inspection
 */
public record TestSuiteRun(
        String suiteRunId,
        String clientRequestId,
        Status status,
        String suiteId,
        long suiteRevision,
        String suiteFingerprint,
        String targetKind,
        String targetId,
        String targetFingerprint,
        String evidenceFingerprint,
        List<CaseResult> caseResults,
        CoverageStatus coverageStatus,
        PromotionStatus promotionStatus,
        List<String> promotionReasons,
        TestSuiteRunAttestation attestation,
        JsonNode rawResponse
) {
    /** Aggregate suite execution states. */
    public enum Status {
        /** A durable checkpoint exists and execution may continue. */
        RUNNING,
        /** Every case and coverage requirement passed. */
        PASSED,
        /** Scheduling completed with case or coverage failures. */
        COMPLETED_WITH_FAILURES,
        /** Preflight or fail-fast left cases unscheduled. */
        PARTIAL,
        /** Required child or aggregate evidence cannot be proven. */
        EVIDENCE_INCOMPLETE
    }

    /** Per-case scheduling and evidence states. */
    public enum CaseStatus {
        /** The case has not reached a terminal state. */
        PENDING,
        /** The child run and its declared assertions passed. */
        PASSED,
        /** The child run or an assertion failed. */
        FAILED,
        /** Fail-fast or preflight prevented scheduling. */
        NOT_SCHEDULED,
        /** The child evidence identity or completeness check failed. */
        EVIDENCE_INCOMPLETE
    }

    /** Aggregate coverage states. */
    public enum CoverageStatus {
        /** Coverage is not yet evaluated for a running checkpoint. */
        NOT_EVALUATED,
        /** Every declared structural requirement is satisfied. */
        SATISFIED,
        /** At least one declared structural requirement is missing. */
        UNSATISFIED,
        /** Coverage cannot be proven because execution or evidence is incomplete. */
        INCOMPLETE
    }

    /** Promotion-policy input states; eligibility is not certification. */
    public enum PromotionStatus {
        /** Policy has not yet been evaluated. */
        NOT_EVALUATED,
        /** Evidence is eligible for a later external gate. */
        ELIGIBLE,
        /** Stable policy reasons prevent eligibility. */
        BLOCKED
    }

    /** Mutually exclusive meaning of a suite run. */
    public enum EvaluationMode {
        /** Graph or operator business behavior was executed and structural coverage was evaluated. */
        BUSINESS_EXECUTION,
        /** Inputs were checked by the exact shared schema validator without invoking the target. */
        SCHEMA_ADMISSION
    }

    /** Typed lifecycle of one schema-admission case. */
    public enum AdmissionCaseStatus {
        /** Validator evaluation has not started. */
        PENDING,
        /** Plan provenance and validator observations exactly match the stored expectation. */
        MATCHED,
        /** The validator outcome or diagnostics differ from the stored expectation. */
        EXPECTATION_MISMATCH,
        /** The stored case no longer belongs to the exact reviewed boundary plan. */
        PROVENANCE_MISMATCH,
        /** A complete independently usable observation could not be produced. */
        EVIDENCE_INCOMPLETE,
        /** Fail-fast or lease loss prevented validator evaluation. */
        NOT_SCHEDULED
    }

    /** Schema-admission expectation and observation outcomes. */
    public enum AdmissionOutcome {
        /** The exact shared validator accepted the input. */
        ACCEPTED,
        /** The exact shared validator rejected the input. */
        SCHEMA_REJECTED
    }

    /** Aggregate schema-admission coverage states. */
    public enum AdmissionCoverageStatus {
        /** No case has reached validator evaluation. */
        NOT_EVALUATED,
        /** Every exact case produced the expected validator result. */
        SATISFIED,
        /** Every case completed but at least one expectation or provenance check failed. */
        UNSATISFIED,
        /** At least one required case lacks complete evidence. */
        INCOMPLETE
    }

    /**
     * Payload-free observation for one exact schema-admission case.
     *
     * @param caseId suite-local case identity
     * @param status typed admission status
     * @param expectedOutcome immutable suite expectation
     * @param observedOutcome validator observation, or null before/in lieu of evaluation
     * @param expectedValidationCodes exact expected validator codes
     * @param observedValidationCodes exact observed validator codes
     * @param diagnosticCode stable mismatch or evidence-failure code
     */
    public record AdmissionCaseResult(
            String caseId,
            AdmissionCaseStatus status,
            AdmissionOutcome expectedOutcome,
            AdmissionOutcome observedOutcome,
            List<String> expectedValidationCodes,
            List<String> observedValidationCodes,
            String diagnosticCode
    ) {
        /** Normalizes bounded protocol values and rejects contradictory match claims. */
        public AdmissionCaseResult {
            caseId = normalized(caseId);
            expectedValidationCodes = immutableCodes(expectedValidationCodes);
            observedValidationCodes = immutableCodes(observedValidationCodes);
            diagnosticCode = machineCode(diagnosticCode, "admission diagnostic code");
            if (caseId.isBlank() || status == null || expectedOutcome == null) {
                throw new IllegalArgumentException("Admission case result is incomplete");
            }
            if (status == AdmissionCaseStatus.MATCHED
                    && (observedOutcome != expectedOutcome
                    || !observedValidationCodes.equals(expectedValidationCodes)
                    || !diagnosticCode.isBlank())) {
                throw new IllegalArgumentException("Matched admission result must exactly match");
            }
            if (List.of(AdmissionCaseStatus.EXPECTATION_MISMATCH,
                    AdmissionCaseStatus.PROVENANCE_MISMATCH).contains(status)
                    && (observedOutcome == null || diagnosticCode.isBlank())) {
                throw new IllegalArgumentException(
                        "Admission mismatch requires an observation and diagnostic code");
            }
        }

        /**
         * Reports whether the planned expectation and shared-validator observation match exactly.
         *
         * @return true only for an exact plan and validator match
         */
        public boolean matched() {
            return status == AdmissionCaseStatus.MATCHED;
        }
    }

    /**
     * Strongly typed aggregate schema-admission verdict.
     *
     * @param status aggregate admission state
     * @param requiredCases exact suite case count
     * @param evaluatedCases cases that reached the validator
     * @param matchedCases cases whose observations exactly matched expectations
     * @param expectationMismatchCaseIds validator expectation mismatches
     * @param provenanceMismatchCaseIds reviewed-plan provenance mismatches
     * @param incompleteCaseIds cases without complete evidence
     * @param allCasesCompleted whether every exact case completed
     */
    public record AdmissionCoverage(
            AdmissionCoverageStatus status,
            int requiredCases,
            int evaluatedCases,
            int matchedCases,
            List<String> expectationMismatchCaseIds,
            List<String> provenanceMismatchCaseIds,
            List<String> incompleteCaseIds,
            boolean allCasesCompleted
    ) {
        /** Canonicalizes case identities and enforces fail-closed aggregate counters. */
        public AdmissionCoverage {
            expectationMismatchCaseIds = immutableIds(expectationMismatchCaseIds);
            provenanceMismatchCaseIds = immutableIds(provenanceMismatchCaseIds);
            incompleteCaseIds = immutableIds(incompleteCaseIds);
            if (status == null || requiredCases < 1 || evaluatedCases < 0 || matchedCases < 0
                    || evaluatedCases > requiredCases || matchedCases > evaluatedCases) {
                throw new IllegalArgumentException("Admission coverage counters are inconsistent");
            }
            boolean satisfied = status == AdmissionCoverageStatus.SATISFIED
                    && allCasesCompleted && evaluatedCases == requiredCases
                    && matchedCases == requiredCases && expectationMismatchCaseIds.isEmpty()
                    && provenanceMismatchCaseIds.isEmpty() && incompleteCaseIds.isEmpty();
            boolean notEvaluated = status == AdmissionCoverageStatus.NOT_EVALUATED
                    && evaluatedCases == 0 && matchedCases == 0 && !allCasesCompleted;
            if (status == AdmissionCoverageStatus.SATISFIED && !satisfied
                    || status == AdmissionCoverageStatus.NOT_EVALUATED && !notEvaluated) {
                throw new IllegalArgumentException("Admission coverage status is contradictory");
            }
        }
    }

    /** Semantic coverage states emitted only by suite evidence v2. */
    public enum SemanticCoverageStatus {
        /** A running checkpoint has not evaluated semantic coverage. */
        NOT_EVALUATED,
        /** Every typed semantic requirement has trusted observations. */
        SATISFIED,
        /** Complete trusted evidence is missing at least one required fact. */
        UNSATISFIED,
        /** Evidence fidelity or completeness cannot prove at least one fact. */
        INCOMPLETE
    }

    /**
     * One payload-free semantic requirement whose trusted source fact was unavailable.
     *
     * @param requirementId stable suite-local requirement identity
     * @param reasonCode stable reason the fact could not be evaluated
     */
    public record SemanticUnavailable(String requirementId, String reasonCode) {
        /** Normalizes stable identities and reason codes. */
        public SemanticUnavailable {
            requirementId = normalized(requirementId);
            reasonCode = machineCode(reasonCode, "semantic unavailable reason");
            if (requirementId.isBlank() || reasonCode.isBlank()) {
                throw new IllegalArgumentException("Semantic unavailable fact is incomplete");
            }
        }
    }

    /**
     * Strongly typed payload-free projection of a v2 semantic coverage verdict.
     *
     * @param status aggregate semantic coverage state
     * @param requiredRequirementIds exact signed requirement identities
     * @param observedRequirementIds requirements with trusted observations
     * @param missingRequirementIds requirements absent from complete trusted evidence
     * @param unavailable requirements whose source facts could not be evaluated
     */
    public record SemanticCoverage(
            SemanticCoverageStatus status,
            List<String> requiredRequirementIds,
            List<String> observedRequirementIds,
            List<String> missingRequirementIds,
            List<SemanticUnavailable> unavailable
    ) {
        /** Defensively snapshots verdict collections. */
        public SemanticCoverage {
            requiredRequirementIds = immutableIds(requiredRequirementIds);
            observedRequirementIds = immutableIds(observedRequirementIds);
            missingRequirementIds = immutableIds(missingRequirementIds);
            unavailable = unavailable == null ? List.of() : List.copyOf(unavailable);
            if (status == null || requiredRequirementIds.isEmpty()) {
                throw new IllegalArgumentException("Semantic coverage verdict is incomplete");
            }
        }

        private static List<String> immutableIds(List<String> values) {
            if (values == null) {
                return List.of();
            }
            return values.stream().map(TestSuiteRun::normalized).toList();
        }
    }

    /**
     * Payload-free projection of one governed suite case.
     *
     * @param caseId suite-local case id
     * @param caseType GOLDEN, NEGATIVE, BOUNDARY, REGRESSION, or governed PROPERTY
     * @param status scheduling and child-evidence outcome
     * @param runId persisted child run id, blank when unscheduled
     * @param evidenceStatus child test-run status
     * @param evidenceClass EXPLORATORY or CERTIFIABLE
     * @param fixtureBundleId exact fixture id
     * @param fixtureRevision exact fixture revision
     * @param fixtureFingerprint exact fixture fingerprint
     * @param assertionsEvaluated evaluated assertion count
     * @param assertionsPassed passing assertion count
     * @param diagnosticCode stable failure code without free-form details
     */
    public record CaseResult(
            String caseId,
            String caseType,
            CaseStatus status,
            String runId,
            String evidenceStatus,
            String evidenceClass,
            String fixtureBundleId,
            long fixtureRevision,
            String fixtureFingerprint,
            int assertionsEvaluated,
            int assertionsPassed,
            String diagnosticCode
    ) {
        /** Normalizes identity fields and rejects impossible counters. */
        public CaseResult {
            caseId = normalized(caseId);
            caseType = normalized(caseType);
            runId = normalized(runId);
            evidenceStatus = normalized(evidenceStatus);
            evidenceClass = normalized(evidenceClass);
            fixtureBundleId = normalized(fixtureBundleId);
            fixtureFingerprint = normalized(fixtureFingerprint);
            diagnosticCode = machineCode(diagnosticCode, "case diagnostic code");
            if (caseId.isBlank() || !List.of(
                    "GOLDEN", "NEGATIVE", "BOUNDARY", "REGRESSION", "PROPERTY")
                    .contains(caseType) || status == null || fixtureBundleId.isBlank()
                    || fixtureRevision < 1 || !fingerprint(fixtureFingerprint)
                    || assertionsEvaluated < 0 || assertionsPassed < 0
                    || assertionsPassed > assertionsEvaluated) {
                throw new IllegalArgumentException("Suite case result is incomplete or inconsistent");
            }
            boolean hasRun = !runId.isBlank();
            boolean hasEvidenceStatus = !evidenceStatus.isBlank();
            boolean hasEvidenceClass = !evidenceClass.isBlank();
            if (!(hasRun == hasEvidenceStatus && hasRun == hasEvidenceClass)
                    || hasEvidenceStatus && !validEvidenceStatus(evidenceStatus)
                    || hasEvidenceClass && !List.of("EXPLORATORY", "CERTIFIABLE").contains(evidenceClass)) {
                throw new IllegalArgumentException("Suite case child-evidence identity is inconsistent");
            }
            boolean businessPassing = hasRun && "PASSED".equals(evidenceStatus)
                    && assertionsPassed == assertionsEvaluated;
            boolean admissionPassing = !hasRun && assertionsEvaluated == 0 && assertionsPassed == 0;
            if (status == CaseStatus.PASSED && !businessPassing && !admissionPassing) {
                throw new IllegalArgumentException(
                        "A passing suite case requires business child evidence or admission-only shape");
            }
        }

        /**
         * Indicates whether this case has passing terminal child evidence.
         *
         * @return true only when this case has a passing terminal child result
         */
        public boolean passed() {
            return status == CaseStatus.PASSED;
        }

        /** @return true when this compatibility case proves no business child was invoked */
        boolean admissionOnly() {
            return runId.isBlank() && evidenceStatus.isBlank() && evidenceClass.isBlank()
                    && assertionsEvaluated == 0 && assertionsPassed == 0;
        }
    }

    /** Normalizes collections and protects the decoded response from caller mutation. */
    public TestSuiteRun {
        suiteRunId = normalized(suiteRunId);
        clientRequestId = normalized(clientRequestId);
        suiteId = normalized(suiteId);
        suiteFingerprint = normalized(suiteFingerprint);
        targetKind = normalized(targetKind);
        targetId = normalized(targetId);
        targetFingerprint = normalized(targetFingerprint);
        evidenceFingerprint = normalized(evidenceFingerprint);
        caseResults = caseResults == null ? List.of() : List.copyOf(caseResults);
        promotionReasons = promotionReasons == null ? List.of() : promotionReasons.stream()
                .map(reason -> machineCode(reason, "promotion reason"))
                .toList();
        attestation = attestation == null ? TestSuiteRunAttestation.unsigned() : attestation;
        if (suiteRunId.isBlank() || clientRequestId.isBlank() || status == null || suiteId.isBlank()
                || suiteRevision < 1 || !fingerprint(suiteFingerprint)
                || !List.of("GRAPH", "OPERATOR").contains(targetKind)
                || targetId.isBlank() || !fingerprint(targetFingerprint) || caseResults.isEmpty()
                || coverageStatus == null || promotionStatus == null) {
            throw new IllegalArgumentException("Suite-run evidence identity is incomplete");
        }
        if (status != Status.RUNNING && !fingerprint(evidenceFingerprint)) {
            throw new IllegalArgumentException("Terminal suite-run evidence requires a full fingerprint");
        }
        AdmissionProjection admission = admissionProjection(rawResponse);
        if (admission == null && status == Status.PASSED
                && (coverageStatus != CoverageStatus.SATISFIED
                || caseResults.stream().anyMatch(result -> !result.passed()))) {
            throw new IllegalArgumentException(
                    "A passing business suite run requires passing cases and coverage");
        }
        if (admission != null) {
            validateAdmissionRun(status, coverageStatus, promotionStatus, promotionReasons,
                    caseResults, admission);
        }
        rawResponse = rawResponse == null ? null : rawResponse.deepCopy();
    }

    /**
     * Projects a historical v1, structural v2, semantic v3, or admission v4 suite response without
     * copying case payloads into reportable fields.
     *
     * @param response decoded suite execution response
     * @return immutable suite-run projection
     */
    public static TestSuiteRun from(JsonNode response) {
        TestingProtocolSchemaValidator.require(response, "testSuiteExecutionResponse");
        JsonNode evidence = response.path("evidence");
        if (!response.path("suiteRunId").asText().equals(evidence.path("suiteRunId").asText())) {
            throw new IllegalArgumentException("Suite-run response identity is inconsistent");
        }
        JsonNode suiteRef = evidence.path("suiteRef");
        JsonNode target = evidence.path("target");
        List<CaseResult> cases = new ArrayList<>();
        evidence.path("caseResults").forEach(value -> {
            JsonNode fixture = value.path("fixtureBundleRef");
            cases.add(new CaseResult(value.path("caseId").asText(), value.path("caseType").asText(),
                    enumValue(CaseStatus.class, value.path("status").asText(), "case status"),
                    value.path("runId").asText(), nullableText(value.path("evidenceStatus")),
                    nullableText(value.path("evidenceClass")), fixture.path("fixtureBundleId").asText(),
                    fixture.path("revision").asLong(), fixture.path("fingerprint").asText(),
                    value.path("assertionsEvaluated").asInt(), value.path("assertionsPassed").asInt(),
                    value.path("diagnosticCode").asText()));
        });
        List<String> reasons = new ArrayList<>();
        evidence.path("promotion").path("reasons").forEach(reason -> reasons.add(reason.asText()));
        String responseVersion = response.path("schemaVersion").asText();
        TestSuiteRunAttestation attestation = List.of(
                TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V2,
                TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V3,
                TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V4).contains(responseVersion)
                ? TestSuiteRunAttestation.from(response.path("attestation"))
                : TestSuiteRunAttestation.unsigned();
        return new TestSuiteRun(response.path("suiteRunId").asText(evidence.path("suiteRunId").asText()),
                evidence.path("clientRequestId").asText(),
                enumValue(Status.class, evidence.path("status").asText(), "status"),
                suiteRef.path("suiteId").asText(), suiteRef.path("revision").asLong(),
                suiteRef.path("fingerprint").asText(), target.path("kind").asText(),
                target.path("id").asText(), target.path("fingerprint").asText(),
                response.path("evidenceFingerprint").asText(), cases,
                enumValue(CoverageStatus.class, evidence.path("coverage").path("status").asText(),
                        "coverage status"),
                enumValue(PromotionStatus.class, evidence.path("promotion").path("status").asText(),
                        "promotion status"), reasons, attestation, response);
    }

    /**
     * Indicates whether execution and structural coverage both passed.
     *
     * @return true only for an internally consistent passing aggregate
     */
    public boolean passed() {
        return evaluationMode() == EvaluationMode.BUSINESS_EXECUTION
                && status == Status.PASSED && coverageStatus == CoverageStatus.SATISFIED
                && caseResults.stream().allMatch(CaseResult::passed);
    }

    /**
     * Indicates whether exact reviewed inputs produced every expected schema-admission result.
     * This never implies that graph or operator business behavior ran.
     *
     * @return true only for terminal satisfied admission evidence
     */
    public boolean admissionPassed() {
        return admissionCoverage().filter(value -> status == Status.PASSED
                && value.status() == AdmissionCoverageStatus.SATISFIED
                && admissionResults().stream().allMatch(AdmissionCaseResult::matched)).isPresent();
    }

    /**
     * Applies the success predicate belonging to this run's explicit evaluation mode.
     *
     * @return business execution success or schema-admission success, never a conflation of both
     */
    public boolean evaluationPassed() {
        return evaluationMode() == EvaluationMode.SCHEMA_ADMISSION ? admissionPassed() : passed();
    }

    /**
     * Returns the mutually exclusive meaning of this evidence generation.
     *
     * @return schema admission for evidence v3, otherwise business execution
     */
    public EvaluationMode evaluationMode() {
        return admissionProjection(rawResponse) == null
                ? EvaluationMode.BUSINESS_EXECUTION : EvaluationMode.SCHEMA_ADMISSION;
    }

    /**
     * Returns ordered typed schema-admission observations.
     *
     * @return immutable empty list for business-execution evidence
     */
    public List<AdmissionCaseResult> admissionResults() {
        AdmissionProjection projection = admissionProjection(rawResponse);
        return projection == null ? List.of() : projection.results();
    }

    /**
     * Returns schema-admission coverage when the producer emitted evidence v3.
     *
     * @return empty for business-execution evidence
     */
    public Optional<AdmissionCoverage> admissionCoverage() {
        AdmissionProjection projection = admissionProjection(rawResponse);
        return projection == null ? Optional.empty() : Optional.of(projection.coverage());
    }

    /**
     * Requires schema-admission support instead of treating structural coverage as equivalent.
     *
     * @return typed admission coverage
     * @throws IllegalStateException when this is business-execution evidence
     */
    public AdmissionCoverage requireAdmissionCoverage() {
        return admissionCoverage().orElseThrow(() -> new IllegalStateException(
                "SCHEMA_ADMISSION_COVERAGE_UNAVAILABLE"));
    }

    /**
     * Indicates whether this evidence may be submitted to a later gate.
     *
     * @return true only for the policy eligibility verdict; never implies certification
     */
    public boolean promotionEligible() {
        return promotionStatus == PromotionStatus.ELIGIBLE;
    }

    /**
     * Returns semantic coverage when the producer emitted v2 aggregate evidence.
     *
     * @return empty for historical structural evidence v1
     */
    public Optional<SemanticCoverage> semanticCoverage() {
        JsonNode evidence = rawResponse == null ? null : rawResponse.path("evidence");
        if (evidence == null || !TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V2.equals(
                evidence.path("schemaVersion").asText())) {
            return Optional.empty();
        }
        JsonNode value = evidence.path("semanticCoverage");
        List<String> required = new ArrayList<>();
        value.path("required").forEach(item -> required.add(item.path("requirementId").asText()));
        List<String> observed = new ArrayList<>();
        value.path("observed").forEach(item -> observed.add(item.path("requirementId").asText()));
        List<String> missing = new ArrayList<>();
        value.path("missingRequirementIds").forEach(item -> missing.add(item.asText()));
        List<SemanticUnavailable> unavailable = new ArrayList<>();
        value.path("unavailable").forEach(item -> unavailable.add(new SemanticUnavailable(
                item.path("requirementId").asText(), item.path("reasonCode").asText())));
        return Optional.of(new SemanticCoverage(enumValue(SemanticCoverageStatus.class,
                value.path("status").asText(), "semantic coverage status"), required, observed,
                missing, unavailable));
    }

    /**
     * Requires semantic coverage support instead of silently treating v1 as an empty verdict.
     *
     * @return typed semantic verdict
     * @throws IllegalStateException with a stable code when only v1 evidence is available
     */
    public SemanticCoverage requireSemanticCoverage() {
        return semanticCoverage().orElseThrow(() -> new IllegalStateException(
                "SEMANTIC_COVERAGE_UNAVAILABLE"));
    }

    /**
     * Returns stable, payload-free gate failure codes for CI output.
     *
     * @param requirePromotionEligible whether policy eligibility is mandatory
     * @return deterministic de-duplicated failure codes
     */
    public List<String> gateFailureCodes(boolean requirePromotionEligible) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        if (status != Status.PASSED) {
            codes.add("SUITE_STATUS_" + status.name());
        }
        if (evaluationMode() == EvaluationMode.SCHEMA_ADMISSION) {
            AdmissionCoverage admission = requireAdmissionCoverage();
            if (admission.status() != AdmissionCoverageStatus.SATISFIED) {
                codes.add("SCHEMA_ADMISSION_" + admission.status().name());
            }
            if (admissionResults().stream().anyMatch(result -> !result.matched())) {
                codes.add("ADMISSION_CASE_FAILURES_PRESENT");
            }
        } else {
            if (coverageStatus != CoverageStatus.SATISFIED) {
                codes.add("COVERAGE_" + coverageStatus.name());
            }
            semanticCoverage().filter(value -> value.status() != SemanticCoverageStatus.SATISFIED)
                    .ifPresent(value -> codes.add("SEMANTIC_COVERAGE_" + value.status().name()));
            if (caseResults.stream().anyMatch(result -> !result.passed())) {
                codes.add("CASE_FAILURES_PRESENT");
            }
        }
        if (requirePromotionEligible && promotionStatus != PromotionStatus.ELIGIBLE) {
            codes.add("PROMOTION_" + promotionStatus.name());
            codes.addAll(promotionReasons);
        }
        return List.copyOf(codes);
    }

    /**
     * Requires the response to describe the exact caller-owned suite execution intent.
     *
     * @param expectedSuiteId requested suite id
     * @param expectedRevision requested immutable revision
     * @param expectedFingerprint requested suite fingerprint
     * @param expectedClientRequestId caller-owned idempotency key
     */
    void requireExecutionIdentity(String expectedSuiteId, long expectedRevision,
                                  String expectedFingerprint, String expectedClientRequestId) {
        if (!suiteId.equals(normalized(expectedSuiteId)) || suiteRevision != expectedRevision
                || !suiteFingerprint.equals(normalized(expectedFingerprint))
                || !clientRequestId.equals(normalized(expectedClientRequestId))) {
            throw new IllegalArgumentException("Suite-run response identity does not match the request");
        }
    }

    /**
     * Requires this response to describe the requested durable suite run.
     *
     * @param expectedSuiteRunId requested durable run id
     */
    void requireRunIdentity(String expectedSuiteRunId) {
        if (!suiteRunId.equals(normalized(expectedSuiteRunId))) {
            throw new IllegalArgumentException("Suite-run response identity does not match the request");
        }
    }

    /**
     * Returns the authorized complete response without exposing mutable state.
     *
     * @return defensive copy of the authorized complete response
     */
    @Override
    public JsonNode rawResponse() {
        return rawResponse == null ? null : rawResponse.deepCopy();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, normalized(value));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Unknown or missing suite-run " + field);
        }
    }

    private static String nullableText(JsonNode value) {
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static String machineCode(String value, String field) {
        String normalized = normalized(value);
        if (!normalized.isBlank() && !normalized.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
            throw new IllegalArgumentException(field + " must be a stable machine code");
        }
        return normalized;
    }

    private static boolean validEvidenceStatus(String value) {
        return List.of("PASSED", "ASSERTION_FAILED", "EXECUTION_FAILED", "CONTROL_PLAN_REJECTED",
                "FIXTURE_UNMATCHED", "FIXTURE_UNUSED", "CONTROL_PLAN_UNAVAILABLE",
                "EVIDENCE_INCOMPLETE", "CANCELLED", "TIMED_OUT").contains(value);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean fingerprint(String value) {
        return normalized(value).matches("sha256:[0-9a-f]{64}");
    }

    private static AdmissionProjection admissionProjection(JsonNode response) {
        JsonNode evidence = response == null ? null : response.path("evidence");
        if (evidence == null || !TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V3.equals(
                evidence.path("schemaVersion").asText())) {
            return null;
        }
        List<AdmissionCaseResult> results = new ArrayList<>();
        evidence.path("admissionResults").forEach(value -> {
            List<String> expectedCodes = new ArrayList<>();
            value.path("expectedValidationCodes").forEach(code -> expectedCodes.add(code.asText()));
            List<String> observedCodes = new ArrayList<>();
            value.path("observedValidationCodes").forEach(code -> observedCodes.add(code.asText()));
            String observed = nullableText(value.path("observedOutcome"));
            results.add(new AdmissionCaseResult(value.path("caseId").asText(),
                    enumValue(AdmissionCaseStatus.class, value.path("status").asText(),
                            "admission case status"),
                    enumValue(AdmissionOutcome.class, value.path("expectedOutcome").asText(),
                            "expected admission outcome"),
                    observed.isBlank() ? null : enumValue(AdmissionOutcome.class, observed,
                            "observed admission outcome"),
                    expectedCodes, observedCodes, value.path("diagnosticCode").asText()));
        });
        JsonNode value = evidence.path("admissionCoverage");
        AdmissionCoverage coverage = new AdmissionCoverage(
                enumValue(AdmissionCoverageStatus.class, value.path("status").asText(),
                        "admission coverage status"),
                value.path("requiredCases").asInt(), value.path("evaluatedCases").asInt(),
                value.path("matchedCases").asInt(), strings(value.path("expectationMismatchCaseIds")),
                strings(value.path("provenanceMismatchCaseIds")),
                strings(value.path("incompleteCaseIds")), value.path("allCasesCompleted").asBoolean());
        return new AdmissionProjection(List.copyOf(results), coverage);
    }

    private static void validateAdmissionRun(
            Status status, CoverageStatus coverageStatus, PromotionStatus promotionStatus,
            List<String> promotionReasons, List<CaseResult> cases, AdmissionProjection admission) {
        boolean exactClosure = cases.size() == admission.results().size()
                && admission.coverage().requiredCases() == cases.size();
        for (int index = 0; exactClosure && index < cases.size(); index++) {
            exactClosure = cases.get(index).caseId().equals(admission.results().get(index).caseId());
        }
        if (coverageStatus != CoverageStatus.NOT_EVALUATED
                || promotionStatus != PromotionStatus.BLOCKED
                || !promotionReasons.contains("SCHEMA_ADMISSION_ONLY")
                || !promotionReasons.contains("BUSINESS_EXECUTION_NOT_PERFORMED")
                || cases.stream().anyMatch(result -> !result.admissionOnly()) || !exactClosure) {
            throw new IllegalArgumentException(
                    "Schema admission evidence cannot claim business execution or coverage");
        }
        AdmissionCoverageStatus expected = switch (status) {
            case RUNNING -> null;
            case PASSED -> AdmissionCoverageStatus.SATISFIED;
            case COMPLETED_WITH_FAILURES -> AdmissionCoverageStatus.UNSATISFIED;
            case PARTIAL, EVIDENCE_INCOMPLETE -> AdmissionCoverageStatus.INCOMPLETE;
        };
        if (expected == null
                ? !List.of(AdmissionCoverageStatus.NOT_EVALUATED,
                AdmissionCoverageStatus.INCOMPLETE).contains(admission.coverage().status())
                : admission.coverage().status() != expected) {
            throw new IllegalArgumentException(
                    "Schema admission aggregate status and coverage are inconsistent");
        }
    }

    private static List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static List<String> immutableIds(List<String> values) {
        List<String> result = values == null ? List.of()
                : values.stream().map(TestSuiteRun::normalized).toList();
        if (result.stream().anyMatch(String::isBlank)
                || new LinkedHashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException("Admission case identities must be unique and non-empty");
        }
        return List.copyOf(result);
    }

    private static List<String> immutableCodes(List<String> values) {
        List<String> result = values == null ? List.of()
                : values.stream().map(TestSuiteRun::normalized).toList();
        if (result.stream().anyMatch(String::isBlank)
                || new LinkedHashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException("Admission validator codes must be unique and non-empty");
        }
        return List.copyOf(result);
    }

    private record AdmissionProjection(
            List<AdmissionCaseResult> results,
            AdmissionCoverage coverage
    ) {
    }
}
