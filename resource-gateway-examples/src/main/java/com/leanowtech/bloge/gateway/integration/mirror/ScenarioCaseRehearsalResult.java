package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content-addressed, payload-free terminal interpretation of one compiled Scenario case.
 *
 * <p>A result either binds one independently verified Mirror evidence bundle and the complete
 * handling-assertion result closure, or records a bounded pre-evidence failure. A missing or
 * incomplete evidence fact can never produce {@link Outcome#PASS}. Warning assertions remain
 * visible but do not silently acquire blocker semantics.</p>
 *
 * @param schemaVersion exact case-result protocol version
 * @param resultFingerprint canonical fingerprint with this field blanked
 * @param caseIndex zero-based order frozen by the compiled plan
 * @param scenarioCaseRef exact ScenarioCase
 * @param caseType business coverage intent
 * @param testSuiteRef exact governed TestSuite
 * @param testCaseId exact case inside the TestSuite
 * @param mirrorPlanRef exact MirrorPlan generation
 * @param fixtureBundleRef exact fixture generation
 * @param sessionCheckpointRef optional exact stateful recovery fence
 * @param childRequestId deterministic Mirror child idempotency key
 * @param outcome server-derived correctness outcome
 * @param runId exact Mirror run, or blank when no evidence was produced
 * @param evidenceBundleFingerprint exact signed bundle, or blank with no evidence
 * @param evidenceStatus exact Mirror evidence status, or {@code null} with no evidence
 * @param evidenceClass exact Mirror evidence class, or {@code null} with no evidence
 * @param assertionResults complete ordered assertion closure when evidence exists
 * @param diagnosticCode stable payload-free reason for non-passing or pre-evidence outcomes
 * @param startedAt server case start time
 * @param completedAt terminal case time
 */
public record ScenarioCaseRehearsalResult(
        String schemaVersion,
        String resultFingerprint,
        int caseIndex,
        MirrorArtifactRef scenarioCaseRef,
        ScenarioCase.CaseType caseType,
        MirrorArtifactRef testSuiteRef,
        String testCaseId,
        MirrorArtifactRef mirrorPlanRef,
        MirrorArtifactRef fixtureBundleRef,
        MirrorArtifactRef sessionCheckpointRef,
        String childRequestId,
        Outcome outcome,
        String runId,
        String evidenceBundleFingerprint,
        MirrorRunEvidence.Status evidenceStatus,
        MirrorRunEvidence.EvidenceClass evidenceClass,
        List<ScenarioHandlingAssertionResult> assertionResults,
        String diagnosticCode,
        Instant startedAt,
        Instant completedAt
) {
    /** Current payload-free case-result protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioCaseRehearsalResult.v1";
    /** Maximum handling-assertion results admitted for one case. */
    public static final int MAXIMUM_ASSERTIONS = ScenarioCase.MAXIMUM_ASSERTIONS;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern REQUEST_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern MACHINE_CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    /** Conservative case outcomes consumed by aggregate gates. */
    public enum Outcome {
        /** Execution evidence and every blocker assertion proved the case. */
        PASS,
        /** Execution or at least one blocker assertion deterministically failed. */
        FAIL,
        /** Correctness could not be established from complete trusted evidence. */
        INDETERMINATE
    }

    /** Validates one complete evidence or pre-evidence result shape. */
    public ScenarioCaseRehearsalResult {
        schemaVersion = version(schemaVersion);
        resultFingerprint = optionalFingerprint(
                resultFingerprint, "resultFingerprint");
        if (caseIndex < 0 || caseIndex >= ScenarioPack.MAXIMUM_CASES) {
            throw new IllegalArgumentException("caseIndex is outside the rehearsal bound");
        }
        scenarioCaseRef = exactKind(
                scenarioCaseRef, "SCENARIO_CASE", "scenarioCaseRef");
        caseType = Objects.requireNonNull(caseType, "caseType");
        testSuiteRef = exactKind(testSuiteRef, "TEST_SUITE", "testSuiteRef");
        testCaseId = identifier(testCaseId, "testCaseId");
        mirrorPlanRef = exactKind(
                mirrorPlanRef, "MIRROR_PLAN", "mirrorPlanRef");
        fixtureBundleRef = exactKind(
                fixtureBundleRef, "FIXTURE_BUNDLE", "fixtureBundleRef");
        if (sessionCheckpointRef != null) {
            sessionCheckpointRef = exactKind(
                    sessionCheckpointRef,
                    "MIRROR_SESSION_CHECKPOINT",
                    "sessionCheckpointRef");
        }
        childRequestId = requestId(childRequestId);
        outcome = Objects.requireNonNull(outcome, "outcome");
        runId = optionalIdentifier(runId, "runId");
        evidenceBundleFingerprint = optionalFingerprint(
                evidenceBundleFingerprint, "evidenceBundleFingerprint");
        assertionResults = assertionResults == null
                ? List.of() : List.copyOf(assertionResults);
        diagnosticCode = optionalMachineCode(diagnosticCode);
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                    "case result completion precedes its start");
        }
        validateEvidenceClosure(
                mirrorPlanRef, outcome, runId, evidenceBundleFingerprint,
                evidenceStatus, evidenceClass, assertionResults,
                diagnosticCode);
    }

    private static void validateEvidenceClosure(
            MirrorArtifactRef mirrorPlanRef,
            Outcome outcome,
            String runId,
            String evidenceBundleFingerprint,
            MirrorRunEvidence.Status evidenceStatus,
            MirrorRunEvidence.EvidenceClass evidenceClass,
            List<ScenarioHandlingAssertionResult> assertionResults,
            String diagnosticCode) {
        boolean hasRun = !runId.isBlank();
        boolean completeEvidence = hasRun
                && !evidenceBundleFingerprint.isBlank()
                && evidenceStatus != null
                && evidenceClass != null;
        boolean noEvidence = !hasRun
                && evidenceBundleFingerprint.isBlank()
                && evidenceStatus == null
                && evidenceClass == null;
        if (!completeEvidence && !noEvidence) {
            throw new IllegalArgumentException(
                    "case evidence identity must be complete or absent");
        }
        if (noEvidence) {
            if (!assertionResults.isEmpty()
                    || outcome == Outcome.PASS
                    || diagnosticCode.isBlank()) {
                throw new IllegalArgumentException(
                        "pre-evidence case result must fail closed with a reason");
            }
            return;
        }
        if (assertionResults.isEmpty()
                || assertionResults.size() > MAXIMUM_ASSERTIONS) {
            throw new IllegalArgumentException(
                    "evidence-backed case requires bounded assertion results");
        }
        Set<MirrorArtifactRef> refs = new HashSet<>();
        for (ScenarioHandlingAssertionResult result : assertionResults) {
            if (result == null
                    || !refs.add(result.assertionRef())
                    || !runId.equals(result.runId())
                    || !evidenceBundleFingerprint.equals(
                    result.evidenceBundleFingerprint())
                    || !mirrorPlanRef.fingerprint().equals(
                    result.planFingerprint())) {
                throw new IllegalArgumentException(
                        "case assertion results do not bind the exact child evidence");
            }
        }
        Outcome derived = deriveOutcome(evidenceStatus, assertionResults);
        if (outcome != derived
                || outcome == Outcome.PASS && !diagnosticCode.isBlank()
                || outcome != Outcome.PASS && diagnosticCode.isBlank()) {
            throw new IllegalArgumentException(
                    "case outcome and diagnostic must be derived from evidence");
        }
    }

    /**
     * Derives fail-closed case correctness from execution and blocker assertion evidence.
     *
     * @param status exact Mirror evidence status
     * @param results complete handling-assertion result closure
     * @return deterministic case outcome
     */
    public static Outcome deriveOutcome(
            MirrorRunEvidence.Status status,
            List<ScenarioHandlingAssertionResult> results) {
        Objects.requireNonNull(status, "status");
        List<ScenarioHandlingAssertionResult> assertions =
                results == null ? List.of() : results;
        boolean executionFailed = switch (status) {
            case ASSERTION_FAILED, EXECUTION_FAILED, CONTROL_PLAN_REJECTED,
                    FIXTURE_UNMATCHED, FIXTURE_UNUSED, TIMED_OUT -> true;
            case PASSED, CONTROL_PLAN_UNAVAILABLE, EVIDENCE_INCOMPLETE,
                    CANCELLED -> false;
        };
        if (executionFailed
                || assertions.stream().anyMatch(result ->
                result.severity() == CaseHandlingAssertion.Severity.BLOCKER
                        && result.outcome()
                        == ScenarioHandlingAssertionResult.Outcome.FAIL)) {
            return Outcome.FAIL;
        }
        if (status == MirrorRunEvidence.Status.CONTROL_PLAN_UNAVAILABLE
                || status == MirrorRunEvidence.Status.EVIDENCE_INCOMPLETE
                || status == MirrorRunEvidence.Status.CANCELLED
                || assertions.stream().anyMatch(result ->
                result.severity() == CaseHandlingAssertion.Severity.BLOCKER
                        && result.outcome()
                        == ScenarioHandlingAssertionResult.Outcome
                        .INDETERMINATE)) {
            return Outcome.INDETERMINATE;
        }
        return Outcome.PASS;
    }

    /** @return number of failed blocker assertions */
    public long blockerFailures() {
        return assertionCount(
                CaseHandlingAssertion.Severity.BLOCKER,
                ScenarioHandlingAssertionResult.Outcome.FAIL);
    }

    /** @return number of indeterminate blocker assertions */
    public long blockerIndeterminate() {
        return assertionCount(
                CaseHandlingAssertion.Severity.BLOCKER,
                ScenarioHandlingAssertionResult.Outcome.INDETERMINATE);
    }

    /** @return number of failed warning assertions */
    public long warningFailures() {
        return assertionCount(
                CaseHandlingAssertion.Severity.WARNING,
                ScenarioHandlingAssertionResult.Outcome.FAIL);
    }

    /** @return number of indeterminate warning assertions */
    public long warningIndeterminate() {
        return assertionCount(
                CaseHandlingAssertion.Severity.WARNING,
                ScenarioHandlingAssertionResult.Outcome.INDETERMINATE);
    }

    /** @return identical material carrying a replacement canonical fingerprint */
    public ScenarioCaseRehearsalResult withFingerprint(String value) {
        return new ScenarioCaseRehearsalResult(
                schemaVersion, value, caseIndex, scenarioCaseRef, caseType,
                testSuiteRef, testCaseId, mirrorPlanRef, fixtureBundleRef,
                sessionCheckpointRef, childRequestId, outcome, runId,
                evidenceBundleFingerprint, evidenceStatus, evidenceClass,
                assertionResults, diagnosticCode, startedAt, completedAt);
    }

    /** Keeps child evidence and assertion details out of generic application logs. */
    @Override
    public String toString() {
        return "ScenarioCaseRehearsalResult[caseIndex=" + caseIndex
                + ", scenarioCaseRef=" + scenarioCaseRef
                + ", outcome=" + outcome
                + ", runId=" + runId
                + ", assertionResults=" + assertionResults.size() + "]";
    }

    private long assertionCount(
            CaseHandlingAssertion.Severity severity,
            ScenarioHandlingAssertionResult.Outcome assertionOutcome) {
        return assertionResults.stream()
                .filter(result -> result.severity() == severity
                        && result.outcome() == assertionOutcome)
                .count();
    }

    private static String version(String value) {
        String normalized = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported scenario case rehearsal result schemaVersion");
        }
        return normalized;
    }

    private static MirrorArtifactRef exactKind(
            MirrorArtifactRef value, String kind, String field) {
        if (value == null || !kind.equals(value.kind())) {
            throw new IllegalArgumentException(
                    field + " must be an exact " + kind + " ref");
        }
        return value;
    }

    private static String identifier(String value, String field) {
        String normalized = required(value, field);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String optionalIdentifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank() && !IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String requestId(String value) {
        String normalized = required(value, "childRequestId");
        if (!REQUEST_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("childRequestId is invalid");
        }
        return normalized;
    }

    private static String optionalMachineCode(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank() && !MACHINE_CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("diagnosticCode is invalid");
        }
        return normalized;
    }

    private static String optionalFingerprint(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank() && !FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be blank or canonical SHA-256");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
