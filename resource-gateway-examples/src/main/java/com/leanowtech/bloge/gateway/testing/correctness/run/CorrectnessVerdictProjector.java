package com.leanowtech.bloge.gateway.testing.correctness.run;

import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict.AssertionVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict.CoverageVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict.EvidenceVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict.ExecutionVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict.GateVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict.ProofLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict.Reason;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict.Remediation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceProtocol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Projects five independent correctness axes from trusted aggregate suite evidence. */
public final class CorrectnessVerdictProjector {

    public CorrectnessVerdict project(
            TestSuiteExecutionResponse response,
            CorrectnessPreflightReport.ProofLevel proofLevel,
            boolean publicationCurrent
    ) {
        if (response == null || response.evidence() == null || proofLevel == null) {
            throw new IllegalArgumentException(
                    "Suite evidence and preflight proof level are required");
        }
        TestSuiteRunEvidenceProtocol evidence = response.evidence();
        ExecutionVerdict execution = execution(evidence.status());
        AssertionVerdict assertions = assertions(evidence);
        CoverageVerdict coverage = coverage(evidence.coverage().status());
        boolean terminallyVerifiable = response.attestation().terminallyVerifiable();
        EvidenceVerdict evidenceVerdict = evidence(
                response.evidenceFingerprint(), publicationCurrent, terminallyVerifiable,
                assertions, coverage);
        GateVerdict gate = execution == ExecutionVerdict.SUCCESS
                && assertions == AssertionVerdict.PASSED
                && coverage == CoverageVerdict.COMPLETE
                && evidenceVerdict == EvidenceVerdict.CURRENT
                && terminallyVerifiable
                ? GateVerdict.ACCEPTED : GateVerdict.BLOCKED;
        List<Reason> reasons = reasons(
                execution, assertions, coverage, evidenceVerdict, terminallyVerifiable);
        return new CorrectnessVerdict(
                execution, assertions, coverage, evidenceVerdict, gate,
                ProofLevel.valueOf(proofLevel.name()), reasons, remediations(reasons));
    }

    private static ExecutionVerdict execution(TestSuiteRunEvidence.Status status) {
        return switch (status) {
            case RUNNING -> ExecutionVerdict.RUNNING;
            case PASSED -> ExecutionVerdict.SUCCESS;
            case COMPLETED_WITH_FAILURES -> ExecutionVerdict.FAILED;
            case PARTIAL, EVIDENCE_INCOMPLETE -> ExecutionVerdict.PARTIAL;
        };
    }

    private static AssertionVerdict assertions(TestSuiteRunEvidenceProtocol evidence) {
        int evaluated = evidence.caseResults().stream()
                .mapToInt(TestSuiteRunEvidence.CaseResult::assertionsEvaluated).sum();
        if (evaluated == 0) return AssertionVerdict.NONE;
        boolean failed = evidence.caseResults().stream().anyMatch(result ->
                result.assertionsPassed() < result.assertionsEvaluated());
        if (failed) return AssertionVerdict.FAILED;
        boolean incomplete = evidence.caseResults().stream().anyMatch(result ->
                result.status() == TestSuiteRunEvidence.CaseStatus.PENDING
                        || result.status() == TestSuiteRunEvidence.CaseStatus.NOT_SCHEDULED
                        || result.status() == TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE);
        return incomplete ? AssertionVerdict.INCONCLUSIVE : AssertionVerdict.PASSED;
    }

    private static CoverageVerdict coverage(TestSuiteRunEvidence.CoverageStatus status) {
        return switch (status) {
            case NOT_EVALUATED -> CoverageVerdict.NOT_EVALUATED;
            case SATISFIED -> CoverageVerdict.COMPLETE;
            case UNSATISFIED, INCOMPLETE -> CoverageVerdict.INCOMPLETE;
        };
    }

    private static EvidenceVerdict evidence(
            String evidenceFingerprint,
            boolean publicationCurrent,
            boolean terminallyVerifiable,
            AssertionVerdict assertions,
            CoverageVerdict coverage
    ) {
        if (evidenceFingerprint == null || evidenceFingerprint.isBlank()) {
            return EvidenceVerdict.NONE;
        }
        if (!publicationCurrent) return EvidenceVerdict.STALE;
        if (!terminallyVerifiable || assertions == AssertionVerdict.NONE
                || coverage != CoverageVerdict.COMPLETE) {
            return EvidenceVerdict.EXPLORATORY;
        }
        return EvidenceVerdict.CURRENT;
    }

    private static List<Reason> reasons(
            ExecutionVerdict execution,
            AssertionVerdict assertions,
            CoverageVerdict coverage,
            EvidenceVerdict evidence,
            boolean terminallyVerifiable
    ) {
        List<Reason> reasons = new ArrayList<>();
        switch (execution) {
            case RUNNING -> reasons.add(reason("EXECUTION_RUNNING", "EXECUTION"));
            case FAILED -> reasons.add(reason("EXECUTION_FAILED", "EXECUTION"));
            case PARTIAL -> reasons.add(reason("EXECUTION_PARTIAL", "EXECUTION"));
            default -> { }
        }
        switch (assertions) {
            case NONE -> reasons.add(reason("UNPROVEN", "ASSERTIONS"));
            case FAILED -> reasons.add(reason("ASSERTION_FAILED", "ASSERTIONS"));
            case INCONCLUSIVE -> reasons.add(reason("ASSERTION_INCONCLUSIVE", "ASSERTIONS"));
            case NOT_EVALUATED -> reasons.add(reason("ASSERTION_NOT_EVALUATED", "ASSERTIONS"));
            default -> { }
        }
        switch (coverage) {
            case NOT_EVALUATED -> reasons.add(reason("COVERAGE_NOT_EVALUATED", "COVERAGE"));
            case INCOMPLETE -> reasons.add(reason("COVERAGE_INCOMPLETE", "COVERAGE"));
            case UNFROZEN -> reasons.add(reason("DENOMINATOR_NOT_FROZEN", "COVERAGE"));
            case STALE -> reasons.add(reason("COVERAGE_STALE", "COVERAGE"));
            default -> { }
        }
        if (evidence == EvidenceVerdict.NONE) {
            reasons.add(reason("EVIDENCE_NONE", "EVIDENCE"));
        } else if (evidence == EvidenceVerdict.STALE) {
            reasons.add(reason("EVIDENCE_STALE", "EVIDENCE"));
        }
        if (!terminallyVerifiable) {
            reasons.add(reason("EVIDENCE_UNATTESTED", "EVIDENCE"));
        }
        return reasons.stream().distinct().sorted(Comparator.comparing(Reason::code)).toList();
    }

    private static Reason reason(String code, String axis) {
        return new Reason(code, axis, "correctness.reason." + code.toLowerCase());
    }

    private static List<Remediation> remediations(List<Reason> reasons) {
        return reasons.stream().map(reason -> switch (reason.code()) {
            case "UNPROVEN", "ASSERTION_NOT_EVALUATED", "ASSERTION_INCONCLUSIVE" ->
                    new Remediation("OPEN_ASSERTION_BUILDER", reason.code());
            case "ASSERTION_FAILED", "EXECUTION_FAILED", "EXECUTION_PARTIAL" ->
                    new Remediation("OPEN_FAILED_CASES", reason.code());
            case "COVERAGE_NOT_EVALUATED", "COVERAGE_INCOMPLETE",
                    "DENOMINATOR_NOT_FROZEN", "COVERAGE_STALE" ->
                    new Remediation("OPEN_COVERAGE_MATRIX", reason.code());
            case "EVIDENCE_STALE" -> new Remediation("RERUN_PUBLICATION", reason.code());
            case "EVIDENCE_UNATTESTED" ->
                    new Remediation("CONFIGURE_EVIDENCE_ATTESTATION", reason.code());
            default -> new Remediation("REFRESH_RUN", reason.code());
        }).distinct().toList();
    }
}
