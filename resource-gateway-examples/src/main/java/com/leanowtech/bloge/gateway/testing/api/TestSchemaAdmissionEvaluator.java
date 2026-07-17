package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Revalidates one immutable schema-admission suite without invoking its business target.
 *
 * <p>Preparation rejects target, schema, plan, or suite-provenance drift before a run starts.
 * Case evaluation then proves both exact membership in the regenerated plan and the current
 * shared validator outcome. Results carry only stable codes and fingerprints; suite inputs never
 * enter evidence.</p>
 */
final class TestSchemaAdmissionEvaluator {
    static final String TARGET_CONFLICT = "RG.TEST.SUITE_ADMISSION_TARGET_CONFLICT";
    static final String INPUT_SCHEMA_CONFLICT =
            "RG.TEST.SUITE_ADMISSION_INPUT_SCHEMA_CONFLICT";
    static final String BOUNDARY_PLAN_CONFLICT =
            "RG.TEST.SUITE_ADMISSION_BOUNDARY_PLAN_CONFLICT";
    static final String BOUNDARY_PLAN_UNAVAILABLE =
            "RG.TEST.SUITE_ADMISSION_BOUNDARY_PLAN_UNAVAILABLE";
    static final String SUITE_PROVENANCE_INVALID =
            "RG.TEST.SUITE_ADMISSION_PROVENANCE_INVALID";
    static final String CASE_PROVENANCE_MISMATCH =
            "RG.TEST.SUITE_ADMISSION_CASE_PROVENANCE_MISMATCH";
    static final String EXPECTATION_MISMATCH =
            "RG.TEST.SUITE_ADMISSION_EXPECTATION_MISMATCH";
    static final String VALIDATOR_UNAVAILABLE =
            "RG.TEST.SUITE_ADMISSION_VALIDATOR_UNAVAILABLE";

    private final ObjectMapper objectMapper;

    /** @param objectMapper canonical protocol mapper used for payload-free input comparison */
    TestSchemaAdmissionEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Locks execution to the exact current target, input schema, and regenerated boundary plan.
     *
     * @param suite immutable admission suite selected for execution
     * @param current atomically resolved current target
     * @return prepared immutable lookup used by every case evaluation
     * @throws Conflict when any pre-execution lock or provenance fact differs
     */
    PreparedAdmission prepare(TestSuiteV3 suite, TestSchemaAdmissionTarget current) {
        Objects.requireNonNull(suite, "suite");
        Objects.requireNonNull(current, "current");
        requireTarget(suite, current);
        if (!suite.inputSchemaFingerprint()
                .equals(current.boundaryPlan().inputSchemaFingerprint())) {
            throw conflict(INPUT_SCHEMA_CONFLICT,
                    "The current input schema differs from the reviewed suite schema.",
                    Map.of("expectedInputSchemaFingerprint", suite.inputSchemaFingerprint(),
                            "actualInputSchemaFingerprint",
                            current.boundaryPlan().inputSchemaFingerprint()));
        }
        if (current.boundaryPlan().status() == TestBoundaryCasePlan.Status.UNAVAILABLE) {
            throw conflict(BOUNDARY_PLAN_UNAVAILABLE,
                    "The current input schema cannot produce a validator-proven boundary plan.",
                    Map.of("planStatus", current.boundaryPlan().status().name(),
                            "coverageGapCount", current.boundaryPlan().gaps().size()));
        }
        if (!suite.boundaryPlanFingerprint().equals(current.boundaryPlan().planFingerprint())) {
            throw conflict(BOUNDARY_PLAN_CONFLICT,
                    "The regenerated boundary plan differs from the reviewed suite plan.",
                    Map.of("expectedBoundaryPlanFingerprint", suite.boundaryPlanFingerprint(),
                            "actualBoundaryPlanFingerprint",
                            current.boundaryPlan().planFingerprint()));
        }
        requireSuiteProvenance(suite, current.boundaryPlan());
        Map<String, TestBoundaryCasePlan.BoundaryCase> planCases = new LinkedHashMap<>();
        for (TestBoundaryCasePlan.BoundaryCase planCase : current.boundaryPlan().cases()) {
            if (planCases.putIfAbsent(planCase.caseId(), planCase) != null) {
                throw conflict(BOUNDARY_PLAN_CONFLICT,
                        "The regenerated boundary plan contains duplicate case identities.",
                        Map.of("caseId", planCase.caseId()));
            }
        }
        return new PreparedAdmission(current, planCases);
    }

    /**
     * Evaluates one stored suite case against the exact prepared plan and shared validator.
     *
     * @param prepared current target lock produced by {@link #prepare(TestSuiteV3,
     *                 TestSchemaAdmissionTarget)}
     * @param testCase immutable stored case
     * @param expectation immutable suite expectation for {@code testCase}
     * @return payload-free typed admission result
     */
    TestSuiteRunEvidenceV3.AdmissionCaseResult evaluate(
            PreparedAdmission prepared,
            TestSuite.TestCase testCase,
            TestSuiteV3.AdmissionExpectation expectation) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(testCase, "testCase");
        Objects.requireNonNull(expectation, "expectation");
        List<String> expectedCodes = expectation.validationCodes();
        try {
            List<String> observedCodes = VisualSchemaValidator.validateValue(
                            prepared.current().inputSchema(), testCase.input(), "/input").stream()
                    .filter(VisualDiagnostic::error)
                    .map(VisualDiagnostic::code)
                    .distinct()
                    .sorted()
                    .toList();
            TestSuiteV3.ExpectedOutcome observed = observedCodes.isEmpty()
                    ? TestSuiteV3.ExpectedOutcome.ACCEPTED
                    : TestSuiteV3.ExpectedOutcome.SCHEMA_REJECTED;
            TestBoundaryCasePlan.BoundaryCase planCase =
                    prepared.planCases().get(testCase.caseId());
            boolean provenanceMatches = provenanceMatches(testCase, expectation, planCase);
            boolean expectationMatches = observed == expectation.expectedOutcome()
                    && observedCodes.equals(expectedCodes);
            TestSuiteRunEvidenceV3.AdmissionCaseStatus status;
            String diagnostic;
            if (!provenanceMatches) {
                status = TestSuiteRunEvidenceV3.AdmissionCaseStatus.PROVENANCE_MISMATCH;
                diagnostic = CASE_PROVENANCE_MISMATCH;
            } else if (!expectationMatches) {
                status = TestSuiteRunEvidenceV3.AdmissionCaseStatus.EXPECTATION_MISMATCH;
                diagnostic = EXPECTATION_MISMATCH;
            } else {
                status = TestSuiteRunEvidenceV3.AdmissionCaseStatus.MATCHED;
                diagnostic = "";
            }
            return new TestSuiteRunEvidenceV3.AdmissionCaseResult(testCase.caseId(), status,
                    expectation.expectedOutcome(), observed, expectedCodes, observedCodes,
                    diagnostic);
        } catch (RuntimeException unavailable) {
            return new TestSuiteRunEvidenceV3.AdmissionCaseResult(testCase.caseId(),
                    TestSuiteRunEvidenceV3.AdmissionCaseStatus.EVIDENCE_INCOMPLETE,
                    expectation.expectedOutcome(), null, expectedCodes, List.of(),
                    VALIDATOR_UNAVAILABLE);
        }
    }

    /** @return ordered pending results for a newly signed admission checkpoint */
    List<TestSuiteRunEvidenceV3.AdmissionCaseResult> pending(TestSuiteV3 suite) {
        Objects.requireNonNull(suite, "suite");
        return suite.cases().stream().map(testCase -> {
            TestSuiteV3.AdmissionExpectation expectation =
                    Objects.requireNonNull(suite.admissionExpectations().get(testCase.caseId()),
                            "admissionExpectation");
            return new TestSuiteRunEvidenceV3.AdmissionCaseResult(testCase.caseId(),
                    TestSuiteRunEvidenceV3.AdmissionCaseStatus.PENDING,
                    expectation.expectedOutcome(), null, expectation.validationCodes(),
                    List.of(), "");
        }).toList();
    }

    /**
     * Derives admission-specific aggregate coverage from the complete ordered result closure.
     *
     * @param results one result per suite case
     * @return deterministic admission coverage verdict
     */
    TestSuiteRunEvidenceV3.AdmissionCoverageVerdict coverage(
            List<TestSuiteRunEvidenceV3.AdmissionCaseResult> results) {
        List<TestSuiteRunEvidenceV3.AdmissionCaseResult> safe =
                results == null ? List.of() : List.copyOf(results);
        int evaluated = 0;
        int matched = 0;
        List<String> expectationMismatches = new ArrayList<>();
        List<String> provenanceMismatches = new ArrayList<>();
        List<String> incomplete = new ArrayList<>();
        for (TestSuiteRunEvidenceV3.AdmissionCaseResult result : safe) {
            switch (result.status()) {
                case MATCHED -> {
                    evaluated++;
                    matched++;
                }
                case EXPECTATION_MISMATCH -> {
                    evaluated++;
                    expectationMismatches.add(result.caseId());
                }
                case PROVENANCE_MISMATCH -> {
                    evaluated++;
                    provenanceMismatches.add(result.caseId());
                }
                case PENDING, EVIDENCE_INCOMPLETE, NOT_SCHEDULED ->
                        incomplete.add(result.caseId());
            }
        }
        boolean allCompleted = incomplete.isEmpty();
        TestSuiteRunEvidenceV3.AdmissionCoverageStatus status;
        if (safe.stream().allMatch(result -> result.status()
                == TestSuiteRunEvidenceV3.AdmissionCaseStatus.PENDING)) {
            status = TestSuiteRunEvidenceV3.AdmissionCoverageStatus.NOT_EVALUATED;
        } else if (!allCompleted) {
            status = TestSuiteRunEvidenceV3.AdmissionCoverageStatus.INCOMPLETE;
        } else if (matched == safe.size()) {
            status = TestSuiteRunEvidenceV3.AdmissionCoverageStatus.SATISFIED;
        } else {
            status = TestSuiteRunEvidenceV3.AdmissionCoverageStatus.UNSATISFIED;
        }
        return new TestSuiteRunEvidenceV3.AdmissionCoverageVerdict(status, safe.size(),
                evaluated, matched, expectationMismatches, provenanceMismatches,
                incomplete, allCompleted);
    }

    /** @return compatibility result that deliberately carries no child run or assertions */
    TestSuiteRunEvidence.CaseResult commonResult(
            TestSuite.TestCase testCase,
            TestSuiteRunEvidenceV3.AdmissionCaseResult result) {
        TestSuiteRunEvidence.CaseStatus status = switch (result.status()) {
            case PENDING -> TestSuiteRunEvidence.CaseStatus.PENDING;
            case MATCHED -> TestSuiteRunEvidence.CaseStatus.PASSED;
            case EXPECTATION_MISMATCH, PROVENANCE_MISMATCH ->
                    TestSuiteRunEvidence.CaseStatus.FAILED;
            case EVIDENCE_INCOMPLETE -> TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE;
            case NOT_SCHEDULED -> TestSuiteRunEvidence.CaseStatus.NOT_SCHEDULED;
        };
        return new TestSuiteRunEvidence.CaseResult(testCase.caseId(), testCase.caseType(),
                testCase.fixtureBundleRef(), status, "", null, null,
                0, 0, result.diagnosticCode(), "");
    }

    private boolean provenanceMatches(
            TestSuite.TestCase testCase,
            TestSuiteV3.AdmissionExpectation expectation,
            TestBoundaryCasePlan.BoundaryCase planCase) {
        return planCase != null
                && ProtocolFingerprint.of(objectMapper, testCase.input())
                .equals(ProtocolFingerprint.of(objectMapper, planCase.input()))
                && expectation.expectedOutcome().name().equals(planCase.expectedOutcome().name())
                && expectation.validationCodes().equals(planCase.validationCodes());
    }

    private static void requireTarget(TestSuiteV3 suite, TestSchemaAdmissionTarget current) {
        TestSuite.Target expected = suite.target();
        TestExecutionApiRequest.Target actual = current.target();
        if (!expected.kind().equals(actual.kind()) || !expected.id().equals(actual.id())
                || !expected.fingerprint().equals(actual.fingerprint())) {
            throw conflict(TARGET_CONFLICT,
                    "The current target differs from the exact suite target snapshot.",
                    Map.of("expectedKind", expected.kind(), "expectedId", expected.id(),
                            "expectedFingerprint", expected.fingerprint(),
                            "actualKind", actual.kind(), "actualId", actual.id(),
                            "actualFingerprint", actual.fingerprint()));
        }
    }

    private static void requireSuiteProvenance(
            TestSuiteV3 suite, TestBoundaryCasePlan plan) {
        Set<String> caseIds = new LinkedHashSet<>();
        suite.cases().forEach(testCase -> caseIds.add(testCase.caseId()));
        Map<String, Object> metadata = suite.metadata();
        boolean partial = plan.status() == TestBoundaryCasePlan.Status.PARTIAL;
        boolean exact = !suite.cases().isEmpty()
                && caseIds.size() == suite.cases().size()
                && suite.admissionExpectations().keySet().equals(caseIds)
                && "schema-boundary-plan".equals(metadata.get("source"))
                && TestSuiteV3.EvaluationMode.SCHEMA_ADMISSION.name()
                .equals(metadata.get("evaluationMode"))
                && plan.status().name().equals(metadata.get("boundaryPlanStatus"))
                && exactInteger(metadata.get("coverageGapCount"), plan.gaps().size())
                && Objects.equals(metadata.get("coverageGapsAccepted"), partial)
                && exactInteger(metadata.get("selectedCaseCount"), suite.cases().size());
        if (!exact) {
            throw conflict(SUITE_PROVENANCE_INVALID,
                    "The suite lacks the exact typed provenance of its reviewed boundary plan.",
                    Map.of("planStatus", plan.status().name(),
                            "coverageGapCount", plan.gaps().size(),
                            "selectedCaseCount", suite.cases().size(),
                            "coverageGapsAccepted", partial));
        }
    }

    private static boolean exactInteger(Object value, int expected) {
        if (!(value instanceof Number number)) {
            return false;
        }
        try {
            return new BigDecimal(String.valueOf(number))
                    .compareTo(BigDecimal.valueOf(expected)) == 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static Conflict conflict(String code, String message, Map<String, Object> details) {
        return new Conflict(code, message, details);
    }

    /** Current target and case lookup proven safe for deterministic case evaluation. */
    record PreparedAdmission(
            TestSchemaAdmissionTarget current,
            Map<String, TestBoundaryCasePlan.BoundaryCase> planCases
    ) {
        /** Freezes the plan lookup used across checkpointed case evaluations. */
        PreparedAdmission {
            current = Objects.requireNonNull(current, "current");
            planCases = Map.copyOf(Objects.requireNonNull(planCases, "planCases"));
        }
    }

    /** Stable fail-closed pre-execution conflict without case payload material. */
    static final class Conflict extends RuntimeException {
        private final String code;
        private final Map<String, Object> details;

        /**
         * @param code stable integration problem code
         * @param message bounded human explanation
         * @param details payload-free expected and actual lock facts
         */
        Conflict(String code, String message, Map<String, Object> details) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
            this.details = details == null ? Map.of() : Map.copyOf(details);
        }

        /** @return stable integration problem code */
        String code() {
            return code;
        }

        /** @return immutable payload-free conflict facts */
        Map<String, Object> details() {
            return details;
        }
    }
}
